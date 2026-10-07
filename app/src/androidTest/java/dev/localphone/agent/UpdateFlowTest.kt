package dev.localphone.agent

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.localphone.agent.updates.*
import dev.localphone.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** Fake public Release HTTP bytes; real SHA, PackageManager signing checks, FileProvider and OS installer. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UpdateFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private val fixture get() = File(context.getExternalFilesDir("update-fixtures"), "next.apk")
    private val badSigner get() = File(context.getExternalFilesDir("update-fixtures"), "untrusted.apk")
    private var scenario: ActivityScenario<MainActivity>? = null
    @Before fun ownedAvdOnly() {
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals("LocalPhoneAgent_API35", device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        assertTrue(BuildConfig.VERSION_CODE > 0)
        assertTrue("Prepare the newer signed AVD update fixture", fixture.isFile)
        assertTrue(badSigner.isFile)
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("wm dismiss-keyguard")
    }
    @After fun restoreOwnedAvd() {
        device.pressBack()
        scenario?.close(); graph.updateClientFactoryOverride = null
        // The host runner restores appops after instrumentation ends. Revoking this app-op while
        // instrumentation is running makes Android kill the agent process, including the test runner.
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }
    private fun await(check: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 20000; var failure: AssertionError? = null
        do { try { check(); return } catch (e: AssertionError) { failure = e }; SystemClock.sleep(100) } while (SystemClock.elapsedRealtime() < deadline)
        throw failure ?: AssertionError("Timed out")
    }
    private class FixtureTransport(private val file: File, private val version: Int = BuildConfig.VERSION_CODE + 1, private val truncated: Boolean = false) : ReleaseTransport {
        val digest = UpdateFiles.sha256(file)
        var apkRequests = 0
        var streamClosed = false
        private val nextName = BuildConfig.VERSION_NAME.substringBefore('-').split('.').map(String::toInt)
            .let { "${it[0]}.${it[1]}.${it[2] + 1}" }
        val base = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/v$nextName/"
        override fun open(url: String): InputStream {
            if (url == GitHubUpdates.latestUrl(BuildConfig.UPDATE_REPOSITORY)) return ByteArrayInputStream("""{
                "draft":false,"prerelease":false,"tag_name":"v$nextName","body":"새 버전 변경 내용: 업데이트 버튼 검사","assets":[
                {"name":"update.json","state":"uploaded","size":1000,"browser_download_url":"${base}update.json"},
                {"name":"LocalPhoneAgent-automation.apk","state":"uploaded","size":${file.length()},"digest":"sha256:$digest","browser_download_url":"${base}LocalPhoneAgent-automation.apk"}]}""".toByteArray())
            if (url == base + "update.json") return ByteArrayInputStream("""{"schemaVersion":1,"packageName":"dev.localphone.agent","channel":"automation",
                "versionCode":$version,"versionName":"$nextName-automation","minSdk":26,"apk":{"name":"LocalPhoneAgent-automation.apk","bytes":${file.length()},"sha256":"$digest"}}""".toByteArray())
            assertEquals(base + GitHubUpdates.APK_NAME, url); apkRequests++
            val input = if (truncated) ByteArrayInputStream(byteArrayOf(1, 2, 3)) else file.inputStream()
            return object : java.io.FilterInputStream(input) { override fun close() { streamClosed = true; super.close() } }
        }
    }
    private fun client(transport: ReleaseTransport) = AppUpdateClient(context, transport)
    @Test fun noNewerVersionDoesNotDownloadAnyApk() = runBlocking {
        val network = FixtureTransport(fixture, BuildConfig.VERSION_CODE)
        assertNull(client(network).check()); assertEquals(0, network.apkRequests)
    }
    @Test fun newerSameSignerApkIsVerifiedAndSharedOnlyWithSystemInstaller() = runBlocking {
        val network = FixtureTransport(fixture); val client = client(network)
        val update = checkNotNull(client.check()); assertEquals(BuildConfig.VERSION_CODE.toLong() + 1, update.versionCode)
        val file = client.download(update) { }
        assertEquals(network.digest, UpdateFiles.sha256(file)); assertTrue(network.streamClosed)
        val intent = client.installIntent(file)
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
        assertEquals("content", intent.data?.scheme); assertEquals("dev.localphone.agent.updates", intent.data?.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(intent.component)
        assertEquals(BuildConfig.VERSION_CODE, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
        val info = context.packageManager.getApplicationInfo(intent.component!!.packageName, 0)
        assertTrue(info.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
    }
    @Test fun samePackageWithDifferentSigningKeyIsRejectedAndDeleted() = runBlocking {
        val network = FixtureTransport(badSigner); val client = client(network)
        val update = checkNotNull(client.check())
        val result = runCatching { client.download(update) { } }
        assertTrue(result.isFailure); assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("서명"))
        assertTrue(File(context.cacheDir, "updates").listFiles().orEmpty().isEmpty())
    }
    @Test fun truncatedDownloadCannotReachInstaller() = runBlocking {
        val network = FixtureTransport(fixture, truncated = true); val client = client(network)
        val result = runCatching { client.download(checkNotNull(client.check())) { } }
        assertTrue(result.isFailure); assertTrue(network.streamClosed)
        assertTrue(File(context.cacheDir, "updates").listFiles().orEmpty().isEmpty())
    }
    @Test fun cancelledDownloadClosesStreamAndRemovesPartialFile() = runBlocking {
        val network = FixtureTransport(fixture); val client = client(network)
        val result = runCatching { client.download(checkNotNull(client.check())) { throw CancellationException("User cancelled") } }
        assertTrue(result.exceptionOrNull() is CancellationException); assertTrue(network.streamClosed)
        assertTrue(File(context.cacheDir, "updates").listFiles().orEmpty().isEmpty())
    }
    private fun launchSettings(network: FixtureTransport) {
        graph.updateClientFactoryOverride = { AppUpdateClient(it, network) }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SETTINGS, true))
        onView(withId(R.id.update_button)).perform(click())
    }
    @Test fun updateButtonDownloadsShowsNotesAndRequestsAndroidInstallSourcePermission() {
        device.executeShellCommand("appops set dev.localphone.agent REQUEST_INSTALL_PACKAGES deny")
        val network = FixtureTransport(fixture); launchSettings(network)
        await { assertEquals("com.android.settings", device.currentPackageName) }
        assertEquals(1, network.apkRequests); assertTrue(network.streamClosed)
        device.pressBack()
        await { assertEquals(context.packageName, device.currentPackageName) }
        scenario!!.onActivity { activity ->
            assertTrue(activity.findViewById<android.widget.TextView>(R.id.update_notes).text.toString().contains("변경 내용"))
            assertEquals("업데이트 설치", activity.findViewById<android.widget.Button>(R.id.update_button).text.toString())
        }
        assertEquals(BuildConfig.VERSION_CODE, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
    }
    @Test fun verifiedUpdateOpensActualSystemConfirmationWithoutSilentInstallation() {
        device.executeShellCommand("appops set dev.localphone.agent REQUEST_INSTALL_PACKAGES allow")
        val network = FixtureTransport(fixture); launchSettings(network)
        await { assertTrue("Actual system installer: ${device.currentPackageName}", device.currentPackageName?.contains("packageinstaller") == true) }
        await { assertTrue(device.hasObject(androidx.test.uiautomator.By.textContains("Local Phone Agent"))) }
        assertEquals(BuildConfig.VERSION_CODE, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
        val evidence = File(context.getExternalFilesDir("emulator-evidence"), "update-installer.txt").apply { parentFile!!.mkdirs() }
        evidence.writeText("ACTUAL_ANDROID_INSTALLER_CONFIRMATION\ninstalled_version_code=${BuildConfig.VERSION_CODE}\nverified_fixture_version_code=${BuildConfig.VERSION_CODE + 1}\nNo confirmation pressed. Not a physical S25 / Play Protect verdict.\n")
        device.pressBack()
        await { assertEquals(context.packageName, device.currentPackageName) }
        assertEquals(BuildConfig.VERSION_CODE, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
    }
}
