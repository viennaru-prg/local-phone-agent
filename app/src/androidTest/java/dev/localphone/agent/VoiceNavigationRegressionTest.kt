package dev.localphone.agent

import android.app.UiAutomation
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.Gson
import dev.localphone.agent.runtime.*
import dev.localphone.agent.ui.VoiceButton
import dev.localphone.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Reported native-zero callback through the automatic entry; real Qwen and OS UI.
 * This does not simulate the S25 microphone or the physical back-tap sensor. */
class VoiceNavigationRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private val uri = Uri.parse("content://dev.localphone.testmap.state/state")
    private lateinit var automation: UiAutomation
    private var services = ""; private var enabled = ""
    private var activity: MainActivity? = null
    private var entry: VoiceInvocationActivity? = null
    private var listener: SpeechInput.Listener? = null
    private val place = UserPlace("synthetic-office-uuid", "테스트 지사", listOf("회사"), Coordinates(36.111111, 128.111111),
        "서울시 테스트로 11", "TEST", PlaceSource.MANUAL, 1, 1)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(value: String) = automation.executeShellCommand(value).use { fd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() }
    }
    private fun await(timeout: Long = 90_000, check: () -> Unit) {
        val until = SystemClock.elapsedRealtime() + timeout; var last: AssertionError? = null
        do { try { check(); return } catch (error: AssertionError) { last = error }; SystemClock.sleep(80) }
        while (SystemClock.elapsedRealtime() < until)
        throw last ?: AssertionError("Timed out")
    }
    private fun state() = context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
        assertTrue(cursor.moveToFirst()); cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)).orEmpty() }
    }
    private fun mode(value: String) { context.contentResolver.update(uri, ContentValues().apply { put("mode", value) }, null, null) }
    @Before fun setup() {
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertEquals("LocalPhoneAgent_API35", shell("getprop ro.boot.qemu.avd_name")); assertEquals("1", shell("getprop ro.kernel.qemu"))
        services = shell("settings get secure enabled_accessibility_services"); enabled = shell("settings get secure accessibility_enabled")
        shell("settings put secure enabled_accessibility_services dev.localphone.agent/dev.localphone.agent.runtime.AgentAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        shell("pm grant dev.localphone.agent android.permission.RECORD_AUDIO")
        graph.agentModelEnabledOverride = null
        graph.settings.put("ui_automation_consent", "yes"); graph.settings.put("voice_onboarded", "yes")
        graph.settings.put("voice_haptics", "no")
        runBlocking { graph.places.all().forEach { graph.places.delete(it.id) }; graph.places.saveConfirmed(place)
            assertTrue(graph.planner.select(LocalModelId.QWEN3).success) }
        graph.speechFactoryOverride = { object : SpeechInput {
            override fun start(value: SpeechInput.Listener) { listener = value; value.onListening() }
            override fun cancel() = Unit
        } }
        await(15_000) { assertTrue(graph.uiAutomation.available) }
    }
    @After fun cleanup() {
        main { entry?.coordinator?.cancel(); entry?.finish(); activity?.finish() }
        graph.speechFactoryOverride = null; graph.agentModelEnabledOverride = null
        runBlocking { graph.planner.close(); graph.places.delete(place.id) }
        graph.settings.put("ui_automation_consent", ""); mode("legacy")
        if (services == "null" || services.isBlank()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services $services")
        if (enabled == "null" || enabled.isBlank()) shell("settings delete secure accessibility_enabled")
        else shell("settings put secure accessibility_enabled $enabled")
    }
    private fun startMain() {
        main { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        await(15_000) { main { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>().singleOrNull() }; assertNotNull(activity) }
    }
    private fun recognition() = SpeechRecognitionResult(listOf(SpeechHypothesis("회사로 안내해 줘", .35f, 0),
        SpeechHypothesis("회사로 안내해줘", .34f, 1), SpeechHypothesis("회사로 가 줘", .33f, 2)), engine = "N_BEST_FIXTURE", onDevice = true)
    private fun reportedNativeZero() = SpeechRecognitionResult(listOf(SpeechHypothesis("회사로 안내해 줘", 0f, 0)),
        listOf("회사", "회사로", "회사로 안", "회사로 안내", "회사로 안내해 줘", "회사로 안내해 줘", "회사로 안내해 줘")
            .mapIndexed { index, text -> SpeechPartial(listOf(SpeechHypothesis(text, null, 0)),
                if (index == 0) 2043 else if (index == 1) 2675 else 2676) },
        engine = "android-native-on-device", onDevice = true, readyLatencyMs = 116, audioDurationMs = 3202,
        finalLatencyMs = 0, totalLatencyMs = 3318, biasCount = 24)
    private fun button(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) button(view.getChildAt(index), text)?.let { return it }
        return null
    }
    private fun hasText(view: View, text: String): Boolean =
        view is TextView && view.text.toString() == text ||
            view is ViewGroup && (0 until view.childCount).any { hasText(view.getChildAt(it), text) }
    private fun record(origin: String, previous: String?, name: String) {
        await {
            val trace = graph.invocationDebug.last
            assertNotEquals(previous, trace?.sessionId); assertEquals(origin, trace?.origin)
        }
        val state = state()
        val directory = File(context.getExternalFilesDir(null), "voice-model-evidence").apply { mkdirs() }
        File(directory, "$name.json").writeText(Gson().toJson(mapOf("scope" to "owned x86_64 AVD; STT callback fixture, real selected Qwen and OS Accessibility; not physical S25",
            "trace" to graph.invocationDebug.last, "speech" to graph.speechDiagnostics.last,
            "response" to graph.planner.lastResponse, "fixture" to state)))
        assertEquals("SUCCESS", graph.invocationDebug.last?.result)
        assertEquals("MODEL_INFERENCE:qwen3", graph.invocationDebug.last?.interpretation)
        assertFalse(graph.uiAutomation.active)
        assertEquals("guidance", state["screen"]); assertEquals("1", state["navigation_starts"])
        assertEquals("0", state["saved_clicks"]); assertEquals("0", state["searches"])
        val link = Uri.parse(state.getValue("uri"))
        assertEquals("36.111111", link.getQueryParameter("dlat")); assertEquals("128.111111", link.getQueryParameter("dlng"))
        // A registered UUID place with the company alias must not gain a second slot
        // identity after guidance, otherwise the next text/voice command is ambiguous.
        assertEquals(listOf(place), runBlocking { graph.places.all() })
    }
    @Test fun centerTextAndAutomaticEntryUseSameRegisteredDestinationAndFinish() {
        mode("acknowledged_navigation"); startMain()
        var previous = graph.invocationDebug.last?.sessionId
        main { activity!!.findViewById<VoiceButton>(R.id.voice_button).performClick() }
        await(10_000) { assertNotNull(listener) }
        main { listener!!.onRecognition(recognition()) }
        record("MANUAL_MICROPHONE", previous, "office-center")
        assertEquals("1", state()["gesture_taps"])

        mode("acknowledged_navigation"); startMain(); previous = graph.invocationDebug.last?.sessionId
        main { activity!!.findViewById<View>(R.id.agent_settings).performClick() }
        // The settings page reads local places asynchronously and temporarily owns the
        // UI work lease. A real user waits for that page to render before pressing Run.
        await(10_000) { main { assertTrue(hasText(activity!!.window.decorView, place.canonicalName)) } }
        main {
            activity!!.findViewById<EditText>(R.id.command_input).setText("회사로 안내해 줘")
            checkNotNull(button(activity!!.window.decorView, "명령 실행")).performClick()
        }
        record("TEXT_INPUT", previous, "office-text")

        mode("hidden_destination_auto_navigation"); previous = graph.invocationDebug.last?.sessionId; listener = null
        main { context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!) }
        await(10_000) { main { entry = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<VoiceInvocationActivity>().singleOrNull() }; assertNotNull(listener)
            assertEquals(InvocationState.LISTENING, entry?.coordinator?.state) }
        main { listener!!.onRecognition(reportedNativeZero()) }
        record("ANDROID_APP_LAUNCH", previous, "office-automatic")
        assertEquals(0f, graph.speechDiagnostics.last!!.resolution.confidence!!, 0f)
        assertFalse(graph.speechDiagnostics.last!!.resolution.requiresClarification)
        assertTrue(graph.speechDiagnostics.last!!.resolution.evidence.contains("NATIVE_ZERO_WITH_STABLE_LITERAL"))
        assertEquals(InvocationState.SUCCESS, entry?.coordinator?.state)
        val completed = entry!!
        await(10_000) { main { assertTrue(completed.isDestroyed) } }
    }
}
