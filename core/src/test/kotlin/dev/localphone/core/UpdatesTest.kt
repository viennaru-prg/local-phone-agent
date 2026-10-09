package dev.localphone.core

import kotlin.test.*

class UpdatesTest {
    private val base = "https://github.com/${UpdateContract.REPOSITORY}/releases"
    private fun release(vararg extra: String) = UpdateContract.release("""{
      "draft":false,"prerelease":false,"tag_name":"v1.0.1","html_url":"$base/tag/v1.0.1","body":"수정 내용",
      "assets":[{"name":"update-agent.json","size":400,"browser_download_url":"$base/download/v1.0.1/update-agent.json"},
      {"name":"LocalPhoneAgent.apk","size":19000000,"browser_download_url":"$base/download/v1.0.1/LocalPhoneAgent.apk"}${extra.joinToString("")}]}
    """)
    private fun manifest() = """{"schemaVersion":1,"channel":"agent-v1","packageName":"dev.localphone.agent","versionCode":101,"versionName":"1.0.1","minSdk":30,
        "apk":{"name":"LocalPhoneAgent.apk","bytes":19000000,"sha256":"${"a".repeat(64)}"}}"""
    @Test fun publicReleaseAndVersionContract() {
        val update = UpdateContract.manifest(release(), manifest())
        assertEquals(101, update.versionCode); assertEquals("1.0.1", update.versionName)
        UpdateContract.verifyArchive(update, UpdateContract.PACKAGE, 101, 30, 35, setOf("signer"), setOf("signer"))
    }
    @Test fun unrelatedPackageOrOlderChannelIsRejected() {
        assertFails { UpdateContract.manifest(release(), manifest().replace("dev.localphone.agent", "other.app")) }
        assertFails { UpdateContract.manifest(release(), manifest().replace("agent-v1", "automation")) }
    }
    @Test fun releaseSizeChecksumAndTagMustMatch() {
        assertFails { UpdateContract.manifest(release(), manifest().replace("19000000", "18000000")) }
        assertFails { UpdateContract.manifest(release(), manifest().replace("a".repeat(64), "bad")) }
        assertFails { UpdateContract.manifest(release(), manifest().replace("1.0.1", "1.0.2")) }
    }
    @Test fun duplicateAssetsCannotSelectAnArbitraryArchive() {
        assertFails { UpdateContract.asset(release(",{\"name\":\"LocalPhoneAgent.apk\",\"size\":1,\"browser_download_url\":\"$base/download/v1.0.1/LocalPhoneAgent.apk\"}"), UpdateContract.APK) }
    }
    @Test fun installedSignerAndRealArchiveVersionAreChecked() {
        val u = UpdateContract.manifest(release(), manifest())
        assertFails { UpdateContract.verifyArchive(u, UpdateContract.PACKAGE, 102, 30, 35, setOf("a"), setOf("a")) }
        assertFails { UpdateContract.verifyArchive(u, UpdateContract.PACKAGE, 101, 30, 35, setOf("a"), setOf("b")) }
        assertFails { UpdateContract.verifyArchive(u, UpdateContract.PACKAGE, 101, 30, 35, emptySet(), emptySet()) }
        assertFails { UpdateContract.verifyArchive(u, UpdateContract.PACKAGE, 101, 30, 29, setOf("a"), setOf("a")) }
    }
    @Test fun redirectsRemainHttpsOnGithubDownloadHosts() {
        assertTrue(UpdateContract.allowedDownload("https://release-assets.githubusercontent.com/a?token=x"))
        for (url in listOf("http://github.com/a", "https://github.com.evil.test/a", "https://github.com@evil.test/a", "https://github.com:123/a", "file:///a"))
            assertFalse(UpdateContract.allowedDownload(url), url)
    }
}
