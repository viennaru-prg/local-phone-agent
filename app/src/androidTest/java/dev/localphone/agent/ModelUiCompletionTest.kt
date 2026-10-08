package dev.localphone.agent

import android.app.UiAutomation
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.localphone.agent.runtime.*
import dev.localphone.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import com.google.gson.Gson
import java.io.File

/** REAL model -> actual OS AccessibilityService -> separate actual Android Views.
 * No structured function or SemanticUi matcher can perform this two-step goal. */
class ModelUiCompletionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private lateinit var automation: UiAutomation
    private var activity: MainActivity? = null
    private var entry: VoiceInvocationActivity? = null
    private var services = ""; private var enabled = ""
    private val uri = Uri.parse("content://dev.localphone.testmap.state/state")
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(value: String) = automation.executeShellCommand(value).use { fd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() }
    }
    private fun await(check: () -> Unit) {
        val until = SystemClock.elapsedRealtime() + 15_000; var last: AssertionError? = null
        do { try { check(); return } catch (error: AssertionError) { last = error }; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < until)
        throw last ?: AssertionError("Timed out")
    }
    private fun state() = context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
        assertTrue(cursor.moveToFirst()); cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)).orEmpty() }
    }
    @Before fun setup() {
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertEquals("LocalPhoneAgent_API35", shell("getprop ro.boot.qemu.avd_name")); assertEquals("1", shell("getprop ro.kernel.qemu"))
        graph.agentModelEnabledOverride = null; assertTrue(graph.useAgentModel)
        services = shell("settings get secure enabled_accessibility_services"); enabled = shell("settings get secure accessibility_enabled")
        shell("settings put secure enabled_accessibility_services dev.localphone.agent/dev.localphone.agent.runtime.AgentAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        graph.settings.put("ui_automation_consent", "yes"); graph.settings.put("voice_onboarded", "yes")
        graph.settings.put("voice_haptics", "no")
        graph.settings.put("ui_place:office", ""); graph.settings.put("ui_place:회사로", "")
        runBlocking { withContext(Dispatchers.IO) { graph.places.all().forEach { graph.places.delete(it.id) } } }
        shell("pm grant dev.localphone.agent android.permission.RECORD_AUDIO")
        context.contentResolver.update(uri, ContentValues().apply { put("mode", "ai_notes") }, null, null)
        await { assertTrue(graph.uiAutomation.available) }
        runBlocking { assertTrue(graph.planner.select(LocalModelId.QWEN3).success) }
    }
    @After fun cleanup() {
        main { entry?.coordinator?.cancel(); entry?.finish(); activity?.finish() }
        runBlocking { graph.planner.close() }
        graph.speechFactoryOverride = null
        graph.settings.put("ui_automation_consent", "")
        context.contentResolver.update(uri, ContentValues().apply { put("mode", "legacy") }, null, null)
        if (services == "null" || services.isBlank()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services $services")
        if (enabled == "null" || enabled.isBlank()) shell("settings delete secure accessibility_enabled")
        else shell("settings put secure accessibility_enabled $enabled")
    }
    private fun evidence(name: String, started: Long, value: Map<String, Any?>) {
        val directory = File(context.getExternalFilesDir(null), "voice-model-evidence").apply { mkdirs() }
        File(directory, "$name.json").writeText(Gson().toJson(value + mapOf("scope" to "owned x86_64 AVD; real Qwen, real OS Accessibility; separate fixture, NOT NAVER on S25",
            "elapsed_ms" to SystemClock.elapsedRealtime() - started, "fixture" to state(), "last_model_output" to graph.planner.lastResponse)))
    }
    private fun save(name: String, started: Long, value: Map<String, Any?>) {
        evidence(name, started, value)
        assertEquals("2", state()["generic_clicks"])
        assertEquals("ai_notes_done", state()["screen"])
        assertEquals("ui_complete", graph.planner.lastResponse?.calls?.single()?.name)
        assertFalse(graph.uiAutomation.active)
        // A completed UI task must not force the next phone command to prefill the entire
        // catalog again. This is a fresh model call; no Android effect in this check.
        runBlocking {
            assertEquals(listOf(Action.OpenSettings(SettingsPage.WIFI)), graph.planner.plan("와이파이 설정 열어줘").actions)
            assertTrue(graph.planner.lastResponse!!.timing!!.reusedInputTokens > 250)
        }
    }
    @Test fun realQwenCompletesMultipleObservedScreens() = runBlocking {
        main { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        await { main { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() }; assertNotNull(activity) }
        val started = SystemClock.elapsedRealtime(); var work: Deferred<AgentOutcome>? = null
        val evidence = mutableMapOf<String, Any?>()
        main { work = activity!!.lifecycleScope.async {
            val engine = AgentEngine(activity!!, graph)
            val request = engine.prepare("네이버지도에서 테스트 메모를 저장해줘", graph.useAgentModel)
            evidence("model-ui-phone-text", started, mapOf("request" to request, "phone_output" to graph.planner.lastResponse))
            // Grounding may reject the phone model's initial tool proposal. Preserve that
            // failure in evidence, then verify real Qwen UI inference completes the original
            // goal. Never replace a bad model proposal with a fabricated model success.
            assertNotNull(graph.planner.lastResponse?.timing)
            assertEquals("네이버지도", request.plan.actions.filterIsInstance<Action.AppTask>().single().appName)
            evidence["request"] = request; evidence["phone_output"] = graph.planner.lastResponse
            engine.execute(request.decision as PolicyDecision.Ready)
        } }
        val outcome = withTimeout(185_000) { work!!.await() }
        evidence["outcome"] = outcome
        save("model-ui-text", started, evidence)
        assertNull(outcome.failure); assertTrue(outcome.execution?.success == true)
    }
    @Test fun backTapEntryKeepsRealModelUiTaskAliveAfterAppSwitch() {
        var listener: SpeechInput.Listener? = null
        graph.speechFactoryOverride = { object : SpeechInput {
            override fun start(value: SpeechInput.Listener) { listener = value; value.onListening() }
            override fun cancel() = Unit
        } }
        main { context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!) }
        await { main { entry = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<VoiceInvocationActivity>().singleOrNull() }
            assertEquals(InvocationState.LISTENING, entry?.coordinator?.state); assertNotNull(listener) }
        val started = SystemClock.elapsedRealtime()
        main { listener!!.onFinal("네이버지도에서 테스트 메모를 저장해줘") }
        val until = started + 185_000
        while (SystemClock.elapsedRealtime() < until && entry?.coordinator?.state?.terminal != true) SystemClock.sleep(100)
        save("model-ui-launcher", started, mapOf("trace" to graph.invocationDebug.last,
            "input" to "STT callback fixture, actual selected model and actual app switch, not PCM/human speech"))
        assertEquals(InvocationState.SUCCESS, entry?.coordinator?.state)
        assertEquals("qwen3", graph.invocationDebug.last?.uiModel)
    }
    @Test fun launcherPlanningSurvivesTemporaryCoverAndFinishesCountdownNavigation() {
        context.contentResolver.update(uri, ContentValues().apply { put("mode", "countdown_navigation") }, null, null)
        var listener: SpeechInput.Listener? = null
        graph.speechFactoryOverride = { object : SpeechInput {
            override fun start(value: SpeechInput.Listener) { listener = value; value.onListening() }
            override fun cancel() = Unit
        } }
        main { context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!) }
        await { main { entry = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<VoiceInvocationActivity>().singleOrNull() }
            assertEquals(InvocationState.LISTENING, entry?.coordinator?.state); assertNotNull(listener) }
        val started = SystemClock.elapsedRealtime()
        main { listener!!.onFinal("회사로 가자") }
        await { assertEquals(InvocationState.PLANNING, entry?.coordinator?.state) }
        // Actual OS onStop while native planning is running. This is an entry lifecycle
        // regression, not a claim that Samsung RegiStar has been tested.
        main { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        await { main { assertTrue(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED).contains(entry)) } }
        assertTrue(entry!!.coordinator!!.active)
        val until = started + 185_000
        while (SystemClock.elapsedRealtime() < until && entry?.coordinator?.state?.terminal != true) SystemClock.sleep(100)
        evidence("model-nav-launcher-covered", started, mapOf("trace" to graph.invocationDebug.last,
            "input" to "STT callback, real native model, actual temporary onStop, countdown fixture; NOT physical RegiStar"))
        assertEquals(InvocationState.SUCCESS, entry?.coordinator?.state)
        assertEquals("MODEL_INFERENCE:qwen3", graph.invocationDebug.last?.interpretation)
        assertEquals(BuildConfig.VERSION_CODE, graph.invocationDebug.last?.appVersionCode)
        assertEquals("qwen3", graph.invocationDebug.last?.selectedModel)
        assertEquals("guidance", state()["screen"]); assertEquals("1", state()["navigation_starts"])
        assertFalse(graph.uiAutomation.active)
        val oldEntry = entry!!
        await { main { assertTrue(oldEntry.isDestroyed) } }
        context.contentResolver.update(uri, ContentValues().apply { put("mode", "countdown_navigation") }, null, null)
        entry = null; listener = null
        main { context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!) }
        await { main { entry = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<VoiceInvocationActivity>().singleOrNull() }
            assertEquals(InvocationState.LISTENING, entry?.coordinator?.state); assertNotNull(listener) }
        val repeatedAt = SystemClock.elapsedRealtime()
        main { listener!!.onFinal("회사로 가자") }
        val repeatUntil = repeatedAt + 185_000
        while (SystemClock.elapsedRealtime() < repeatUntil && entry?.coordinator?.state?.terminal != true) SystemClock.sleep(100)
        evidence("model-nav-launcher-cached", repeatedAt, mapOf("trace" to graph.invocationDebug.last,
            "input" to "same real native Qwen call, successfully cached synthetic provider destination; no reply cache"))
        val trace = graph.invocationDebug.last!!
        assertEquals(InvocationState.SUCCESS, entry?.coordinator?.state)
        assertEquals("MODEL_INFERENCE:qwen3", trace.interpretation)
        assertEquals("guidance", state()["screen"]); assertEquals("0", state()["saved_clicks"])
        assertTrue("After-STT goal budget: ${trace.times}", trace.times.getValue("T8") - trace.times.getValue("T5") < 10_000)
    }
}
