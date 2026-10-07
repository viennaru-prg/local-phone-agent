package dev.localphone.agent.updates

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localphone.agent.AgentApplication
import dev.localphone.agent.BuildConfig
import dev.localphone.core.*
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class UpdateFileProvider : FileProvider()
class NoPublishedUpdate : Exception()
interface ReleaseTransport { fun open(url: String): InputStream }
class GitHubTransport : ReleaseTransport {
    override fun open(url: String): InputStream {
        var current = url
        repeat(6) {
            require(GitHubUpdates.mayDownloadRedirect(current)) { "허용되지 않은 업데이트 주소입니다." }
            val connection = URL(current).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "LocalPhoneAgent/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", if (URI(current).host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    current = URL(URL(current), connection.getHeaderField("Location") ?: error("다운로드 주소가 누락되었습니다.")).toString()
                    connection.disconnect(); return@repeat
                }
                if (status == 404 && url.endsWith("/releases/latest")) throw NoPublishedUpdate()
                check(status == 200) { if (status in listOf(403, 429)) "GitHub 확인 요청이 제한되었습니다. 잠시 후 다시 시도해 주세요." else "GitHub 업데이트 연결 실패 ($status)." }
                return object : FilterInputStream(connection.inputStream) {
                    override fun close() { try { super.close() } finally { connection.disconnect() } }
                }
            } catch (failure: Exception) { connection.disconnect(); throw failure }
        }
        error("GitHub 다운로드 연결이 반복되었습니다.")
    }
}

/** Test injection replaces only the network; hashing, archive parsing and installer routing stay real. */
class AppUpdateClient(private val context: Context, private val transport: ReleaseTransport = GitHubTransport(),
                      private val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()) {
    private suspend fun text(url: String, limit: Int): String = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        transport.open(url).use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { job.ensureActive(); val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= limit) { "업데이트 정보 크기를 초과했습니다." }; output.write(buffer, 0, count) }
            output.toString("UTF-8")
        }
    }
    suspend fun check(): AppUpdate? {
        val release = try { GitHubUpdates.parseRelease(text(GitHubUpdates.latestUrl(BuildConfig.UPDATE_REPOSITORY), 512 * 1024), BuildConfig.UPDATE_REPOSITORY) }
            catch (_: NoPublishedUpdate) { return null }
        val update = GitHubUpdates.parseManifest(text(release.manifestUrl, 32768), release, context.packageName)
        if (update.versionCode <= currentVersionCode) return null
        check(update.minSdk <= Build.VERSION.SDK_INT) { "새 버전에는 Android ${update.minSdk} 이상이 필요합니다." }
        return update
    }
    suspend fun download(update: AppUpdate, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, "${update.versionCode}-${update.sha256.take(12)}.apk")
        val job = currentCoroutineContext().job
        try {
            check(directory.usableSpace > update.bytes + 32 * 1024 * 1024) { "업데이트를 받을 저장 공간이 부족합니다." }
            directory.listFiles()?.filter { it.isFile && it != file && it.extension in listOf("part", "apk") }?.forEach { it.delete() }
            var lastPercent = -1
            transport.open(update.apkUrl).use { input ->
                UpdateFiles.copyVerified(input, file, update.bytes, update.sha256, { job.ensureActive() }) { count ->
                    val percent = (count * 100 / update.bytes).toInt()
                    if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                }
            }
            job.ensureActive(); verifyArchive(file, update); file
        } catch (failure: Exception) { file.delete(); throw failure }
    }
    @Suppress("DEPRECATION")
    fun verifyArchive(file: File, update: AppUpdate) {
        require(update.versionCode > currentVersionCode && file.length() == update.bytes && UpdateFiles.sha256(file) == update.sha256) { "업데이트 파일 검사가 일치하지 않습니다." }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("정상적인 서명 APK가 아닙니다.")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName == context.packageName && code == update.versionCode && archive.versionName == update.versionName) { "APK의 앱 또는 버전 정보가 다릅니다." }
        fun signers(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { cert -> MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        val expected = signers(installed)
        require(expected.isNotEmpty() && signers(archive) == expected) { "현재 앱과 서명이 다른 APK여서 업데이트하지 않습니다." }
    }
    fun canInstall() = context.packageManager.canRequestPackageInstalls()
    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    @Suppress("DEPRECATION")
    fun installIntent(file: File): Intent {
        require(file.canonicalFile.parentFile == File(context.cacheDir, "updates").canonicalFile && file.extension == "apk")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("App update", uri) }
        val pm = context.packageManager
        val candidates = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo }.filter {
            it.packageName != "android" && it.packageName != context.packageName &&
                UserHandle.getUserHandleForUid(it.applicationInfo.uid) == Process.myUserHandle() &&
                it.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val target = candidates.singleOrNull { it.packageName == resolved?.packageName && it.name == resolved.name }
            ?: candidates.singleOrNull() ?: error("이 설치 공간의 Android 설치 프로그램을 찾지 못했습니다.")
        intent.setClassName(target.packageName, target.name)
        return intent
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val update: AppUpdate, val percent: Int) : UpdateState
    data class Ready(val update: AppUpdate, val file: File, val offerInstallation: Boolean = true) : UpdateState
    data object Current : UpdateState
    data class Failed(val message: String) : UpdateState
}
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    val client = (application as AgentApplication).updateClientFactoryOverride?.invoke(application) ?: AppUpdateClient(application)
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    fun checkAndDownload() {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            mutable.value = UpdateState.Checking
            try {
                val update = client.check()
                if (update == null) { mutable.value = UpdateState.Current; return@launch }
                mutable.value = UpdateState.Downloading(update, 0)
                val file = client.download(update) { percent -> mutable.value = UpdateState.Downloading(update, percent) }
                mutable.value = UpdateState.Ready(update, file)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.value = UpdateState.Failed(failure.message ?: "업데이트를 확인하지 못했습니다. 연결을 확인하고 다시 시도해 주세요.") }
        }
    }
    fun installationOffered() { (mutable.value as? UpdateState.Ready)?.let { mutable.value = it.copy(offerInstallation = false) } }
    fun installationFailed(message: String) { mutable.value = UpdateState.Failed(message) }
}
