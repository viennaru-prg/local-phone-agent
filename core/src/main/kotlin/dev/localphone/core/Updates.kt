package dev.localphone.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

data class ReleaseAssets(val tag: String, val notes: String, val manifestUrl: String, val apkUrl: String,
                         val apkBytes: Long, val apiDigest: String?)
data class AppUpdate(val versionCode: Long, val versionName: String, val minSdk: Int, val apkUrl: String,
                     val bytes: Long, val sha256: String, val notes: String)

/** No token, arbitrary URL, package switch, prerelease or downgrade is accepted from the update index. */
object GitHubUpdates {
    const val APK_NAME = "LocalPhoneAgent-automation.apk"
    const val MANIFEST_NAME = "update.json"
    const val DUAL_APK_NAME = "LocalPhoneAgent-dual-model.apk"
    const val DUAL_MANIFEST_NAME = "update-dual.json"
    const val MAX_APK_BYTES = 2L * 1024 * 1024 * 1024
    private fun JsonObject.string(name: String) = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: error("업데이트 정보의 $name 항목이 올바르지 않습니다.")
    private fun JsonObject.integer(name: String): Long {
        val value = get(name)?.toString() ?: error("업데이트 정보가 누락되었습니다.")
        require(Regex("[0-9]+").matches(value)) { "업데이트 숫자 정보가 올바르지 않습니다." }
        return value.toLong()
    }
    fun repository(value: String): String {
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(value) && value.split('/').none { it in listOf(".", "..") }) { "GitHub 저장소 주소가 올바르지 않습니다." }
        return value
    }
    fun latestUrl(repo: String) = "https://api.github.com/repos/${repository(repo)}/releases/latest"
    fun parseRelease(json: String, repo: String): ReleaseAssets {
        require(json.length <= 512 * 1024)
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("draft")?.asBoolean == false && root.get("prerelease")?.asBoolean == false) { "정식 공개 업데이트가 아닙니다." }
        val tag = root.string("tag_name")
        require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag)) { "업데이트 태그가 올바르지 않습니다." }
        val base = "https://github.com/${repository(repo)}/releases/download/$tag/"
        val assets = root.getAsJsonArray("assets").map { it.asJsonObject }
        fun asset(name: String): JsonObject {
            val item = assets.singleOrNull { it.string("name") == name } ?: error("업데이트 파일이 아직 준비되지 않았습니다.")
            require(item.string("state") == "uploaded" && item.string("browser_download_url") == base + name) { "다른 저장소의 업데이트 파일입니다." }
            return item
        }
        val dual = assets.any { it.string("name") == DUAL_APK_NAME || it.string("name") == DUAL_MANIFEST_NAME }
        val apkName = if (dual) DUAL_APK_NAME else APK_NAME
        val manifestName = if (dual) DUAL_MANIFEST_NAME else MANIFEST_NAME
        val apk = asset(apkName); val manifest = asset(manifestName)
        require(manifest.integer("size") in 1..32768 && apk.integer("size") in 1..MAX_APK_BYTES) { "업데이트 파일 크기가 올바르지 않습니다." }
        val digest = apk.get("digest")?.takeUnless { it.isJsonNull }?.asString
        require(digest == null || Regex("sha256:[a-fA-F0-9]{64}").matches(digest))
        return ReleaseAssets(tag, root.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(8000),
            base + manifestName, base + apkName, apk.integer("size"), digest?.substringAfter(':')?.lowercase())
    }
    fun parseManifest(json: String, release: ReleaseAssets, packageName: String): AppUpdate {
        require(json.length <= 32768)
        val root = JsonParser.parseString(json).asJsonObject
        require(root.integer("schemaVersion") == 1L && root.string("packageName") == packageName && root.string("channel") == "automation") { "이 앱용 업데이트가 아닙니다." }
        val code = root.integer("versionCode"); val version = root.string("versionName"); val sdk = root.integer("minSdk")
        require(code in 1..Int.MAX_VALUE && sdk in 26..100 && version == release.tag.removePrefix("v") + "-automation") { "업데이트 버전이 올바르지 않습니다." }
        val apk = root.getAsJsonObject("apk")
        val hash = apk.string("sha256").lowercase()
        val expectedName = release.apkUrl.substringAfterLast('/')
        require(expectedName in setOf(APK_NAME, DUAL_APK_NAME) && apk.string("name") == expectedName && apk.integer("bytes") == release.apkBytes && Regex("[a-f0-9]{64}").matches(hash)) { "APK 정보가 올바르지 않습니다." }
        require(release.apiDigest == null || release.apiDigest == hash) { "GitHub 파일 검사값이 일치하지 않습니다." }
        return AppUpdate(code, version, sdk.toInt(), release.apkUrl, release.apkBytes, hash, release.notes)
    }
    fun mayDownloadRedirect(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.port == -1 && uri.rawUserInfo == null && uri.fragment == null &&
            uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    }.getOrDefault(false)
}

/** Streamed bounded download. A failed or cancelled file never becomes an installable .apk. */
object UpdateFiles {
    fun sha256(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(128 * 1024)
        while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun copyVerified(input: InputStream, file: File, expectedBytes: Long, expectedSha256: String,
                     checkCancelled: () -> Unit = {}, progress: (Long) -> Unit = {}) {
        require(expectedBytes in 1..GitHubUpdates.MAX_APK_BYTES && Regex("[a-f0-9]{64}").matches(expectedSha256))
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, file.name + ".part")
        try {
            val hash = MessageDigest.getInstance("SHA-256"); var total = 0L
            temporary.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= expectedBytes) { "APK 크기가 업데이트 정보와 다릅니다." }
                    output.write(buffer, 0, count); hash.update(buffer, 0, count); progress(total)
                }
            }
            checkCancelled()
            require(total == expectedBytes && hash.digest().joinToString("") { "%02x".format(it) } == expectedSha256) { "APK 다운로드 검사에 실패했습니다. 다시 시도해 주세요." }
            require(!file.exists() || file.delete())
            check(temporary.renameTo(file)) { "업데이트 파일을 보관하지 못했습니다." }
        } finally { temporary.delete() }
    }
}
