package dev.localphone.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphone.agent.updates.AppUpdates
import dev.localphone.core.UpdateContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Opt-in network integration check, only after this exact release has been published. */
@RunWith(AndroidJUnit4::class)
class PublishedUpdateTest {
    @Test fun publicReleaseDownloadsAndMatchesTheInstalledSignedApk() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("published_update") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun read(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "LocalPhoneAgentVerification")
            return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        }
        val release = UpdateContract.release(read("https://api.github.com/repos/${UpdateContract.REPOSITORY}/releases/latest"))
        val update = UpdateContract.manifest(release, read(UpdateContract.asset(release, UpdateContract.MANIFEST).url))
        assertEquals(BuildConfig.VERSION_CODE.toLong(), update.versionCode)
        val updates = AppUpdates(context)
        assertNull("equal installed version offered again", updates.check())
        val file = updates.download(AppUpdates.Available(release, update)) {}
        try {
            fun sha(f: File): String {
                val hash = MessageDigest.getInstance("SHA-256")
                f.inputStream().use { input -> val b = ByteArray(65536); while (true) {
                    val n = input.read(b); if (n < 0) break; hash.update(b, 0, n)
                } }
                return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            assertEquals(update.sha256, sha(file))
            assertEquals("published APK differs from the installed release", sha(File(context.applicationInfo.sourceDir)), sha(file))
            assertTrue(updates.hasPending)
            android.util.Log.i("AgentVerification", "PUBLIC_UPDATE version=${update.versionName} bytes=${file.length()} archiveAndSignerVerified=true")
        } finally { file.delete() }
        Unit
    }
}
