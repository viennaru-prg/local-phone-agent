package dev.localphone.agent

import android.app.UiAutomation
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.lifecycle.lifecycleScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.localphone.agent.data.ProviderPlaceCache
import dev.localphone.agent.runtime.*
import dev.localphone.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Actual AccessibilityService -> actual separate-app Views. Fixtures are explicitly not NAVER/Knox/real audio. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class AccessibilityGoalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private val stateUri = Uri.parse("content://dev.localphone.testmap.state/state")
    private var activity: MainActivity? = null
    private var entry: VoiceInvocationActivity? = null
    private var pending: Deferred<AgentOutcome>? = null
    private var previousServices = ""
    private var previousEnabled = ""
    private lateinit var automation: UiAutomation
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(command: String) = automation.executeShellCommand(command).use { fd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }.trim()
    }
    private fun await(timeout: Long = 16000, check: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + timeout; var failure: Throwable? = null
        do { try { check(); return } catch (error: AssertionError) { failure = error }; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < deadline)
        throw failure ?: AssertionError("Timed out")
    }
    @Before fun prepareOwnedEmulatorAndActualService() {
        graph.agentModelEnabledOverride = false
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        assertEquals("1", shell("getprop ro.kernel.qemu"))
        assertEquals("LocalPhoneAgent_API35", shell("getprop ro.boot.qemu.avd_name"))
        assertTrue(BuildConfig.UI_AUTOMATION_AVAILABLE)
        previousServices = shell("settings get secure enabled_accessibility_services")
        previousEnabled = shell("settings get secure accessibility_enabled")
        shell("settings put secure enabled_accessibility_services dev.localphone.agent/dev.localphone.agent.runtime.AgentAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard") // Only the verified unsecured, task-owned AVD.
        shell("pm grant dev.localphone.agent android.permission.RECORD_AUDIO")
        graph.settings.put("ui_automation_consent", "yes")
        graph.settings.put("cache_ui_places", "yes")
        graph.settings.put("search_enabled", "")
        graph.settings.put("music_package", "")
        graph.settings.put("use_functiongemma", "")
        graph.settings.put("voice_onboarded", "yes")
        listOf("office", "home", "parents_home", "테스트치과", "테스트치과로").forEach { graph.settings.put("ui_place:$it", "") }
        graph.settings.put("ui_place:테스트역", "")
        runBlocking { withContext(Dispatchers.IO) { graph.places.all().forEach { graph.places.delete(it.id) } } }
        mode("saved_office")
        await { assertTrue("OS service must actually be bound", graph.uiAutomation.available) }
    }
    @After fun restoreOwnedEmulatorOnly() {
        graph.agentModelEnabledOverride = null
        main { pending?.cancel(); entry?.coordinator?.cancel(); entry?.finish(); activity?.finish() }
        await { assertFalse(graph.uiAutomation.active) }
        graph.speechFactoryOverride = null; graph.focusFactoryOverride = null
        mode("legacy")
        graph.settings.put("ui_automation_consent", "")
        if (previousServices == "null" || previousServices.isBlank()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services $previousServices")
        if (previousEnabled == "null" || previousEnabled.isBlank()) shell("settings delete secure accessibility_enabled")
        else shell("settings put secure accessibility_enabled $previousEnabled")
    }
    private fun mode(value: String) { assertEquals(1, context.contentResolver.update(stateUri, ContentValues().apply { put("mode", value) }, null, null)) }
    private fun state(): Map<String, String> = context.contentResolver.query(stateUri, null, null, null, null)!!.use { cursor ->
        assertTrue(cursor.moveToFirst()); cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)).orEmpty() }
    }
    private fun count(key: String) = state().getValue(key).toInt()
    private fun startMain() {
        main { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        await { main { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() }; assertNotNull(activity) }
    }
    private fun task(command: String): Deferred<AgentOutcome> {
        startMain()
        main { pending = activity!!.lifecycleScope.async {
            val engine = AgentEngine(activity!!, graph)
            val request = engine.prepare(command, useModel = false)
            assertNull("No setup question for unavailable fast path", request.clarification)
            assertNull(request.failure)
            engine.execute(request.decision as PolicyDecision.Ready)
        } }
        return pending!!
    }
    private fun result(work: Deferred<AgentOutcome>) = runBlocking { withTimeout(30000) { work.await() } }
    private fun office() = runBlocking { withContext(Dispatchers.IO) { graph.places.get("office") } }
    private fun cache() = ProviderPlaceCache(graph.settings).get("회사로")
    private fun success(outcome: AgentOutcome) { assertNull("${outcome.failure?.message}; fixture=${state()}", outcome.failure); assertEquals(true, outcome.execution?.success) }
    private fun clickOverlay(contains: String) {
        var clicked = false
        fun visit(node: AccessibilityNodeInfo) {
            if (!clicked && node.className?.toString()?.contains("Button") == true && node.text?.toString()?.contains(contains) == true)
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; try { visit(child) } finally { child.recycle() } }
        }
        for (window in automation.windows) { val root = window.root ?: continue; try { if (root.packageName?.toString() == context.packageName) visit(root) } finally { root.recycle() } }
        assertTrue("Actual overlay button not found: $contains", clicked)
    }
    private fun hasOverlayText(contains: String): Boolean {
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.text?.toString()?.contains(contains) == true) return true
            for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; try { if (visit(child)) return true } finally { child.recycle() } }
            return false
        }
        for (window in automation.windows) { val root = window.root ?: continue; try { if (root.packageName?.toString() == context.packageName && visit(root)) return true } finally { root.recycle() } }
        return false
    }
    @Test fun mapLaunchNeedsNeitherPlacesNorAccessibilityNorSearchApi() {
        graph.settings.put("ui_automation_consent", "")
        success(result(task("지도 켜줘")))
        await { assertEquals("map", state()["screen"]) }; assertEquals(0, count("saved_clicks")); assertEquals(0, count("searches")); assertNull(office())
    }
    @Test fun absentOfficeUsesSavedUiStartsGuidanceAndCachesObservedCoordinates() {
        assertNull(office()); assertNull(cache())
        val first = result(task("회사로 가자")); success(first)
        assertEquals(listOf("NAVIGATION_ACTIVE_AFTER_START"), first.evidence)
        assertEquals("guidance", state()["screen"]); assertEquals(1, count("saved_clicks")); assertEquals(0, count("searches")); assertEquals(1, count("navigation_starts"))
        assertEquals(Coordinates(36.111111, 128.111111), office()?.coordinates); assertEquals(PlaceSource.UI_VERIFIED, office()?.source); assertNotNull(cache())
        success(result(task("회사로 가자")))
        assertEquals(1, count("saved_clicks")); assertEquals(2, count("navigation_starts"))
        assertTrue(state().getValue("uri").startsWith("nmap://navigation"))
    }
    @Test fun missingLocalOfficeAlsoSearchesInsideProvidersSavedUi() {
        mode("saved_search")
        success(result(task("회사로 가자")))
        assertEquals(1, count("saved_clicks")); assertEquals(1, count("searches")); assertEquals("회사", state()["last_query"]); assertEquals(1, count("navigation_starts"))
    }
    @Test fun publicPlaceWithoutApiCredentialsUsesProviderSearchUi() {
        mode("public_search")
        success(result(task("테스트역 가자")))
        assertEquals(1, count("saved_clicks")); assertEquals(1, count("searches")); assertEquals("테스트역", state()["last_query"]); assertEquals(1, count("navigation_starts"))
    }
    @Test fun actualSavedIdentityCollisionWaitsForUserBeforeNavigation() {
        mode("ambiguous_office")
        val work = task("회사로 가자")
        await { assertTrue(hasOverlayText("같은 이름의 목적지")) }
        assertEquals(0, count("navigation_starts")); assertNull(office()); assertFalse(work.isCompleted)
        clickOverlay("테스트로 22")
        success(result(work)); assertEquals(Coordinates(36.222222, 128.222222), office()?.coordinates)
    }
    @Test fun onePublicBusinessNamedOfficeIsNotAssumedToBeUsersEmployer() {
        mode("public_office")
        val work = task("회사로 가자")
        await { assertTrue(hasOverlayText("실제 회사가 어느 곳")) }
        assertEquals(1, count("saved_clicks")); assertEquals(1, count("searches")); assertEquals(0, count("navigation_starts")); assertNull(office())
        clickOverlay("공공검색로 99")
        success(result(work)); assertEquals(Coordinates(36.444444, 128.444444), office()?.coordinates)
    }
    @Test fun startClickWithoutGuidanceEvidenceCannotSucceedOrCache() {
        mode("failed_navigation")
        val outcome = result(task("회사로 가자"))
        assertEquals(InvocationState.EXECUTION_FAILED, outcome.failure?.state); assertEquals(false, outcome.execution?.success)
        assertEquals(1, count("navigation_starts")); assertNull(office()); assertNull(cache())
    }
    @Test fun coordinatesNotExposedAreNotInventedButProviderIdentityCanBeCached() {
        mode("no_coordinates")
        success(result(task("회사로 가자")))
        assertNull(office()); assertNotNull(cache()); assertEquals("서울시 테스트로 11", cache()?.address)
    }
    @Test fun disablingCacheDoesNotDisableTaskExecution() {
        graph.settings.put("cache_ui_places", "no")
        success(result(task("회사로 가자")))
        assertEquals(1, count("navigation_starts")); assertNull(office()); assertNull(cache())
    }
    @Test fun cancellingAmbiguityCannotCauseLateNavigationOrCacheWrites() {
        mode("ambiguous_office")
        val work = task("회사로 가자")
        await { assertTrue(hasOverlayText("같은 이름의 목적지")) }
        clickOverlay("취소")
        await { assertTrue(work.isCancelled); assertFalse(graph.uiAutomation.active) }
        SystemClock.sleep(600); assertEquals(0, count("navigation_starts")); assertNull(office()); assertNull(cache())
    }
    @Test fun anAppTaskNeedsNoAppSpecificToolOrSavedSelector() {
        mode("generic")
        val outcome = result(task("Map Intent TEST RECEIVER에서 알림 메뉴 눌러줘"))
        success(outcome); assertEquals("notifications", state()["screen"]); assertEquals(1, count("generic_clicks"))
        assertEquals(listOf("REQUESTED_CLICK_AND_SCREEN_CHANGED"), outcome.evidence)
    }
    @Test fun anUnregisteredAppSearchUsesEditableFieldAndObservedSearchButton() {
        mode("public_search")
        val outcome = result(task("Map Intent TEST RECEIVER에서 테스트역 검색해줘"))
        success(outcome); assertEquals(1, count("searches")); assertEquals("테스트역", state()["last_query"])
        assertEquals(listOf("SEARCH_RESULTS_OBSERVED"), outcome.evidence)
    }
    @Test fun staleObservedNodeCannotClickAReplacementOnAnotherScreen() {
        startMain()
        var test: Deferred<Boolean>? = null
        main { test = activity!!.lifecycleScope.async {
            graph.uiAutomation.run(NaverLinks.PACKAGE, "알림 메뉴 눌러줘") {
                context.startActivity(context.packageManager.getLaunchIntentForPackage(NaverLinks.PACKAGE)!!)
                val original = screen(); val target = original.exact("알림 메뉴").single()
                val saved = original.exact("저장").single()
                assertTrue(act(original, UiCommand.Click(saved.token)))
                act(original, UiCommand.Click(target.token))
            }
        } }
        assertFalse(runBlocking { withTimeout(20000) { test!!.await() } }); assertEquals(0, count("generic_clicks"))
    }
    @Test fun missingAccessibilityIsReportedAfterGoalPreparationAndMapLaunch() {
        graph.settings.put("ui_automation_consent", "")
        val outcome = result(task("회사로 가자"))
        assertEquals(InvocationState.PERMISSION_REQUIRED, outcome.failure?.state)
        assertEquals(true, outcome.execution?.launched); assertEquals(false, outcome.execution?.success)
        await { assertEquals("map", state()["screen"]) }; assertEquals(0, count("navigation_starts")); assertNull(office())
    }
    @Test fun homeWorkRegistrationWinsOverACompanyNicknameInGenericFavorites() {
        mode("personal_priority")
        success(result(task("회사로 가자")))
        assertEquals(1, count("home_work_clicks")); assertEquals(0, count("frequent_clicks")); assertEquals(0, count("searches"))
        assertEquals("서울시 전용회사로 20", state()["place"])
        assertEquals(Coordinates(36.202020, 128.202020), office()?.coordinates)
    }
    @Test fun homeCommandUsesProvidersDedicatedHomeWorkAreaWithoutLocalRegistration() {
        mode("personal_home")
        success(result(task("집으로 가자")))
        assertEquals(1, count("home_work_clicks")); assertEquals("서울시 합성집로 10", state()["place"])
        assertEquals(0, count("searches")); assertEquals(1, count("navigation_starts"))
    }
    @Test fun unregisteredHomeWorkFallsThroughToFrequentPlacesWithoutOpeningRegistration() {
        mode("frequent_office")
        success(result(task("회사로 가자")))
        assertEquals(1, count("home_work_clicks")); assertEquals(1, count("frequent_clicks")); assertEquals(0, count("registration_clicks"))
        assertEquals(0, count("searches")); assertEquals("서울시 자주가는회사로 30", state()["place"])
    }
    @Test fun namedDestinationCanBeResolvedFromFrequentPlacesBeforePublicSearch() {
        mode("frequent_named")
        success(result(task("테스트치과로 가자")))
        assertEquals(1, count("frequent_clicks")); assertEquals(0, count("home_work_clicks")); assertEquals(0, count("searches"))
        assertEquals("서울시 자주가는치과로 40", state()["place"]); assertEquals(1, count("navigation_starts"))
    }
    @Test fun duplicateFrequentPlacesAskOnlyAfterObservingActualDestinationCollision() {
        mode("frequent_duplicate")
        val work = task("본가로 가자")
        await { assertTrue(hasOverlayText("같은 이름의 목적지")) }
        assertEquals(1, count("frequent_clicks")); assertEquals(0, count("navigation_starts")); assertEquals(0, count("searches"))
        clickOverlay("합성본가로 60")
        success(result(work)); assertEquals("서울시 합성본가로 60", state()["place"])
    }
    @Test fun allPersonalAreasAreTriedBeforeAskingAboutAPublicCompanyBusiness() {
        mode("personal_missing")
        val work = task("회사로 가자")
        await { assertTrue(hasOverlayText("실제 회사가 어느 곳")) }
        assertEquals(1, count("home_work_clicks")); assertEquals(1, count("frequent_clicks")); assertEquals(0, count("registration_clicks"))
        assertEquals(1, count("searches")); assertEquals(0, count("navigation_starts"))
        clickOverlay("공공검색로 99"); success(result(work))
    }
    @Test fun favoritesInsideMyAreDiscoveredRatherThanOnlyTheBottomSavedList() {
        mode("my_favorites")
        success(result(task("회사로 가자")))
        assertEquals(1, count("my_clicks")); assertEquals(1, count("favorites_clicks")); assertEquals(0, count("saved_clicks"))
        assertEquals(0, count("searches")); assertEquals("서울시 전용회사로 20", state()["place"])
    }
    @Test fun frequentPlacesScrollTheirVerticalListRatherThanTheCategoryStrip() {
        mode("frequent_scroll")
        success(result(task("테스트치과로 가자")))
        assertEquals(1, count("frequent_clicks")); assertEquals("서울시 목록아래로 70", state()["place"])
        assertEquals(0, count("searches")); assertEquals(1, count("navigation_starts"))
    }
    @Test fun staleProviderCacheCannotPreventFreshFrequentPlaceDiscovery() {
        mode("frequent_named")
        ProviderPlaceCache(graph.settings).save(dev.localphone.agent.data.ProviderPlaceReference(
            "테스트치과로", "테스트치과", "서울시 이전치과로 99", NaverLinks.PACKAGE, System.currentTimeMillis()))
        success(result(task("테스트치과로 가자")))
        assertEquals(1, count("searches")); assertEquals(1, count("frequent_clicks")); assertEquals(1, count("navigation_starts"))
        assertEquals("서울시 자주가는치과로 40", state()["place"])
        assertEquals("서울시 자주가는치과로 40", ProviderPlaceCache(graph.settings).get("테스트치과로")?.address)
    }
    private class FakeSpeech : SpeechInput {
        lateinit var listener: SpeechInput.Listener
        var starts = 0; var cancelled = false
        override fun start(listener: SpeechInput.Listener) { this.listener = listener; starts++; listener.onListening() }
        override fun cancel() { cancelled = true }
        override fun close() = cancel()
    }
    private fun launchVoice(fake: FakeSpeech) {
        graph.speechFactoryOverride = { fake }
        main { context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!) }
        await { main { entry = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<VoiceInvocationActivity>().singleOrNull() }; assertEquals(InvocationState.LISTENING, entry?.coordinator?.state) }
    }
    @Test fun voiceForegroundHandoffKeepsGoalAliveAndDuplicateFinalDoesNotNavigateTwice() {
        val fake = FakeSpeech(); launchVoice(fake)
        main { fake.listener.onFinal("회사로 가자"); fake.listener.onFinal("회사로 가자") }
        await(25000) { assertEquals(InvocationState.SUCCESS, entry?.coordinator?.state) }
        assertEquals(1, count("navigation_starts")); assertEquals(1, fake.starts); assertTrue(fake.cancelled); assertFalse(graph.uiAutomation.active)
        val file = File(context.getExternalFilesDir(null), "emulator-evidence/goal-ui-voice-handoff.txt")
        file.parentFile!!.mkdirs(); file.writeText("Actual OS AccessibilityService, separate-app fixture UI, voice Activity onStop handoff survived, navigation start observed once. Not real NAVER or S25/Knox.\n")
    }
    @Test fun voiceCancellationFromActualUiOverlayReleasesSessionAndPreventsLateClicks() {
        mode("ambiguous_office")
        val fake = FakeSpeech(); launchVoice(fake)
        main { fake.listener.onFinal("회사로 가자") }
        await { assertTrue(hasOverlayText("같은 이름의 목적지")) }
        clickOverlay("취소")
        await { assertEquals(InvocationState.CANCELLED, entry?.coordinator?.state); assertFalse(graph.uiAutomation.active) }
        assertEquals(0, count("navigation_starts")); assertNull(office()); assertTrue(fake.cancelled)
    }
}
