package dev.localphone.core

import com.google.gson.JsonParser
import java.net.URI

data class ReleaseAsset(val name: String, val url: String, val bytes: Long)
data class AgentRelease(val tag: String, val notes: String, val page: String, val assets: List<ReleaseAsset>)
data class AgentUpdate(val versionCode: Long, val versionName: String, val minSdk: Int,
                       val apk: ReleaseAsset, val sha256: String)

/** Public repository contract, checked again against the downloaded archive before installation. */
object UpdateContract {
    const val REPOSITORY = "viennaru-prg/local-phone-agent"
    const val PACKAGE = "dev.localphone.agent"
    const val MANIFEST = "update-agent.json"
    const val APK = "LocalPhoneAgent.apk"
    const val MAX_APK_BYTES = 128L * 1024 * 1024

    fun release(text: String): AgentRelease {
        val json = JsonParser.parseString(text).asJsonObject
        require(!json.get("draft").asBoolean && !json.get("prerelease").asBoolean) { "정식 공개 릴리즈가 아닙니다." }
        val tag = json.get("tag_name").asString
        require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][A-Za-z0-9]+)*").matches(tag)) { "릴리즈 태그 형식 오류" }
        val page = json.get("html_url").asString
        require(page == "https://github.com/$REPOSITORY/releases/tag/$tag") { "업데이트 저장소가 다릅니다." }
        val assets = json.getAsJsonArray("assets").map { entry ->
            val a = entry.asJsonObject
            ReleaseAsset(a.get("name").asString, a.get("browser_download_url").asString, a.get("size").asLong)
        }
        return AgentRelease(tag, json.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty(), page, assets)
    }

    fun asset(release: AgentRelease, name: String): ReleaseAsset {
        val asset = release.assets.singleOrNull { it.name == name } ?: error("$name 파일이 릴리즈에 없습니다.")
        require(asset.url == "https://github.com/$REPOSITORY/releases/download/${release.tag}/$name") { "배포 파일 주소가 다릅니다." }
        require(asset.bytes > 0) { "배포 파일이 비어 있습니다." }
        return asset
    }

    fun manifest(release: AgentRelease, text: String): AgentUpdate {
        val j = JsonParser.parseString(text).asJsonObject
        require(j.get("schemaVersion").asInt == 1 && j.get("packageName").asString == PACKAGE &&
            j.get("channel").asString == "agent-v1") { "이 앱의 업데이트 정보가 아닙니다." }
        val code = j.get("versionCode").asLong
        val name = j.get("versionName").asString
        val sdk = j.get("minSdk").asInt
        require(code > 100 && release.tag == "v$name" && sdk >= 30) { "버전 정보 불일치" }
        val a = j.getAsJsonObject("apk")
        require(a.get("name").asString == APK) { "APK 파일 이름 불일치" }
        val asset = asset(release, APK)
        require(asset.bytes == a.get("bytes").asLong && asset.bytes <= MAX_APK_BYTES) { "APK 크기 불일치" }
        val sha = a.get("sha256").asString.lowercase()
        require(Regex("[a-f0-9]{64}").matches(sha)) { "APK SHA-256 형식 오류" }
        return AgentUpdate(code, name, sdk, asset, sha)
    }

    fun verifyArchive(update: AgentUpdate, packageName: String, code: Long, minSdk: Int, deviceSdk: Int,
                      installedSigners: Set<String>, archiveSigners: Set<String>) {
        require(packageName == PACKAGE && code == update.versionCode) { "APK 패키지·버전 불일치" }
        require(minSdk == update.minSdk && minSdk <= deviceSdk) { "이 Android 버전에서 설치할 수 없습니다." }
        require(installedSigners.isNotEmpty() && installedSigners == archiveSigners) { "설치된 앱과 APK 서명이 다릅니다." }
    }

    fun allowedDownload(url: String): Boolean = runCatching {
        val u = URI(url)
        u.scheme == "https" && u.userInfo == null && u.port in listOf(-1, 443) &&
            u.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }.getOrDefault(false)
}
