package dev.localphone.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import dev.localphone.agent.updates.AppUpdates
import dev.localphone.core.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UpdateAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun currentArchive(): Pair<File, AgentUpdate> {
        val file = File(context.applicationInfo.sourceDir)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; hash.update(b, 0, n) } }
        val sha = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return file to AgentUpdate(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME, 30,
            ReleaseAsset(UpdateContract.APK, "", file.length()), sha)
    }
    @Test fun actualInstalledApkPassesArchivePackageVersionAndSignerVerification() {
        val (file, update) = currentArchive()
        AppUpdates(context).verifyArchive(file, update)
    }
    @Test fun anArchiveWithAClaimedDifferentVersionIsRejected() {
        val (file, update) = currentArchive()
        try { AppUpdates(context).verifyArchive(file, update.copy(versionCode = update.versionCode + 1)); fail("version mismatch accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("버전")) }
    }
    @Test fun providerGrantsOnlyTheUpdatesCacheSubdirectory() {
        val allowed = File(context.cacheDir, "updates/sample.apk").apply { parentFile!!.mkdirs(); writeText("fixture") }
        val outside = File(context.cacheDir, "private-diagnostics.json").apply { writeText("private") }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", allowed)
            assertEquals("content", uri.scheme)
            try { FileProvider.getUriForFile(context, "${context.packageName}.updates", outside); fail("private file shared") }
            catch (expected: IllegalArgumentException) { }
        } finally { allowed.delete(); outside.delete() }
    }
}
