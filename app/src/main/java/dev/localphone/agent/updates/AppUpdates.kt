package dev.localphone.agent.updates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.localphone.agent.BuildConfig
import dev.localphone.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** No token is needed. Download and verify automatically after the user presses the update button. */
class AppUpdates(private val context: Context) {
    data class Available(val release: AgentRelease, val update: AgentUpdate)
    private var pending: Pair<AgentUpdate, File>? = null
    val hasPending get() = pending != null

    suspend fun check(): Available? = withContext(Dispatchers.IO) {
        val release = UpdateContract.release(readText("https://api.github.com/repos/${UpdateContract.REPOSITORY}/releases/latest"))
        val manifest = UpdateContract.asset(release, UpdateContract.MANIFEST)
        require(manifest.bytes <= 256 * 1024) { "업데이트 정보가 너무 큽니다." }
        val update = UpdateContract.manifest(release, readText(manifest.url))
        if (update.versionCode <= BuildConfig.VERSION_CODE) return@withContext null
        require(update.minSdk <= Build.VERSION.SDK_INT) { "이 Android 버전은 새 릴리즈를 지원하지 않습니다." }
        Available(release, update)
    }

    suspend fun download(value: Available, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val update = value.update
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "agent-${update.versionCode}.apk")
        val partial = File(dir, "agent-${update.versionCode}.part")
        val connection = connect(update.apk.url)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            connection.contentLengthLong.takeIf { it >= 0 }?.let { require(it == update.apk.bytes) { "APK 전송 크기 불일치" } }
            var bytes = 0L; var previous = -1
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    bytes += count; require(bytes <= update.apk.bytes) { "APK 크기 초과" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    val percent = (bytes * 100 / update.apk.bytes).toInt()
                    if (percent != previous) { previous = percent; progress(percent) }
                }
            } }
            require(bytes == update.apk.bytes && digest.digest().hex() == update.sha256) { "다운로드된 APK 검증 실패. 다시 시도해 주세요." }
            verifyArchive(partial, update)
            if (file.exists()) check(file.delete())
            check(partial.renameTo(file)) { "검증된 APK를 저장하지 못했습니다." }
            pending = update to file
            file
        } finally { connection.disconnect(); partial.delete() }
    }

    fun verifyArchive(file: File, update: AgentUpdate) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("APK를 읽지 못했습니다.")
        UpdateContract.verifyArchive(update, archive.packageName, archive.longVersionCode,
            archive.applicationInfo?.minSdkVersion ?: 0, Build.VERSION.SDK_INT, signers(installed), signers(archive))
    }

    fun canInstall() = context.packageManager.canRequestPackageInstalls()
    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Always opens Android's installer, which obtains the user's install confirmation. */
    fun installPending(): Boolean {
        if (!canInstall()) return false
        val (update, file) = pending ?: return false
        require(file.isFile && file.inputStream().use { MessageDigest.getInstance("SHA-256").apply {
            val buffer = ByteArray(64 * 1024); while (true) { val n = it.read(buffer); if (n < 0) break; update(buffer, 0, n) }
        }.digest().hex() } == update.sha256) { "설치 파일이 변경되었습니다. 다시 받아 주세요." }
        verifyArchive(file, update)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { clipData = ClipData.newRawUri("APK", uri) })
        pending = null
        return true
    }

    private fun signers(info: PackageInfo) = info.signingInfo?.apkContentsSigners.orEmpty()
        .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()

    private fun connect(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            require(UpdateContract.allowedDownload(current)) { "허용되지 않은 배포 주소입니다." }
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "LocalPhoneAgent/${BuildConfig.VERSION_NAME}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val status = c.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                val next = c.getHeaderField("Location"); c.disconnect()
                current = URL(URL(current), next ?: error("배포 서버 이동 주소가 없습니다.")).toString()
            } else {
                if (status !in 200..299) { c.disconnect(); error(if (status == 403 || status == 429) "GitHub 요청 한도입니다. 잠시 후 다시 시도해 주세요." else "배포 서버 오류 ($status)") }
                return c
            }
        }
        error("배포 서버 이동 횟수 초과")
    }

    private fun readText(url: String): String {
        val c = connect(url)
        try {
            val bytes = c.inputStream.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (out.size() <= 256 * 1024) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 256 * 1024 + 1 - out.size()))
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            require(bytes.size <= 256 * 1024) { "업데이트 정보가 너무 큽니다." }
            return bytes.toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
