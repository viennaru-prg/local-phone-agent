package dev.localphone.core

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class UpdatesTest {
    private val repo = "test-owner/local-phone-agent"
    private val hash = "ab".repeat(32)
    private fun release() = """{"draft":false,"prerelease":false,"tag_name":"v0.5.0","body":"업데이트 내용", "assets":[
        {"name":"update.json","state":"uploaded","size":1000,"browser_download_url":"https://github.com/$repo/releases/download/v0.5.0/update.json"},
        {"name":"LocalPhoneAgent-automation.apk","state":"uploaded","size":1234,"digest":"sha256:$hash","browser_download_url":"https://github.com/$repo/releases/download/v0.5.0/LocalPhoneAgent-automation.apk"}]}"""
    private fun manifest() = """{"schemaVersion":1,"packageName":"dev.localphone.agent","channel":"automation","versionCode":5,"versionName":"0.5.0-automation","minSdk":26,"apk":{"name":"LocalPhoneAgent-automation.apk","bytes":1234,"sha256":"$hash"}}"""
    @Test fun publicReleaseManifestPreservesNotesAndActualVersion() {
        val assets = GitHubUpdates.parseRelease(release(), repo)
        val update = GitHubUpdates.parseManifest(manifest(), assets, "dev.localphone.agent")
        assertEquals(5L, update.versionCode); assertEquals("업데이트 내용", update.notes)
        assertEquals(hash, update.sha256); assertEquals(1234L, update.bytes)
    }
    @Test fun swappedRepoOrInsecureAssetCannotBeDownloaded() {
        for (text in listOf(release().replace("https://github.com", "http://github.com"), release().replace("/$repo/", "/another/owner/"), release().replace("v0.5.0/update.json", "v0.5.0/../update.json")))
            assertFails { GitHubUpdates.parseRelease(text, repo) }
    }
    @Test fun draftsPrereleasesAndMissingAssetsAreNotUpdateCandidates() {
        for (text in listOf(release().replace("\"draft\":false", "\"draft\":true"), release().replace("\"prerelease\":false", "\"prerelease\":true"), release().replace("\"name\":\"update.json\"", "\"name\":\"unfinished.json\"")))
            assertFails { GitHubUpdates.parseRelease(text, repo) }
    }
    @Test fun wrongPackageChannelAndFractionalVersionAreRejected() {
        val assets = GitHubUpdates.parseRelease(release(), repo)
        for (text in listOf(manifest().replace("dev.localphone.agent", "dev.other.app"), manifest().replace("\"channel\":\"automation\"", "\"channel\":\"install\""), manifest().replace("\"versionCode\":5", "\"versionCode\":5.1"), manifest().replace("0.5.0-automation", "0.6.0-automation")))
            assertFails { GitHubUpdates.parseManifest(text, assets, "dev.localphone.agent") }
    }
    @Test fun gitHubDigestMustMatchManifestAndSizeIsBounded() {
        val assets = GitHubUpdates.parseRelease(release(), repo)
        assertFails { GitHubUpdates.parseManifest(manifest().replace(hash, "cd".repeat(32)), assets, "dev.localphone.agent") }
        assertFails { GitHubUpdates.parseRelease(release().replace("\"size\":1234", "\"size\":9999999999"), repo) }
    }
    @Test fun redirectAllowlistRejectsHostSpoofingAndCredentials() {
        assertTrue(GitHubUpdates.mayDownloadRedirect("https://release-assets.githubusercontent.com/path?signature=example"))
        for (url in listOf("http://github.com/path", "https://github.com.evil.test/path", "https://github.com@evil.test/file", "https://evil@github.com/file", "file:///update.apk", "https://github.com:8080/file"))
            assertFalse(GitHubUpdates.mayDownloadRedirect(url), url)
    }
    @Test fun successfulDownloadRequiresExactLengthAndHash() {
        val directory = Files.createTempDirectory("update-verified").toFile()
        try {
            val data = "verified update bytes".toByteArray()
            val checksum = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            val file = java.io.File(directory, "test.apk")
            UpdateFiles.copyVerified(ByteArrayInputStream(data), file, data.size.toLong(), checksum)
            assertContentEquals(data, file.readBytes()); assertEquals(checksum, UpdateFiles.sha256(file))
            assertFalse(java.io.File(directory, "test.apk.part").exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun corruptTruncatedOversizedOrCancelledDownloadNeverBecomesApk() {
        val directory = Files.createTempDirectory("update-rejected").toFile()
        try {
            val data = byteArrayOf(1, 2, 3)
            for ((index, size) in listOf(3L, 4L, 2L).withIndex()) {
                val file = java.io.File(directory, "$index.apk")
                assertFails { UpdateFiles.copyVerified(ByteArrayInputStream(data), file, size, hash) }
                assertFalse(file.exists()); assertFalse(java.io.File(directory, "$index.apk.part").exists())
            }
            val file = java.io.File(directory, "cancelled.apk")
            assertFails { UpdateFiles.copyVerified(ByteArrayInputStream(data), file, 3, hash, { throw java.util.concurrent.CancellationException() }) }
            assertFalse(file.exists()); assertFalse(java.io.File(directory, "cancelled.apk.part").exists())
        } finally { directory.deleteRecursively() }
    }
}
