package dev.localphone.agent

import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import dev.localphone.agent.runtime.*
import dev.localphone.agent.ui.VoiceButton
import dev.localphone.core.*
import org.junit.*
import org.junit.Assert.*

/** Owned AVD only; synthetic callbacks exercise the real entry owners and encrypted store. */
class InvocationDiagnosticsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private val sources = mutableListOf<FakeSpeech>()
    private val scenarios = mutableListOf<ActivityScenario<*>>()
    private var previousSetting = ""
    private class FakeSpeech : SpeechInput {
        lateinit var listener: SpeechInput.Listener
        override fun start(listener: SpeechInput.Listener) { this.listener = listener; listener.onListening() }
        override fun cancel() = Unit
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(check: () -> Unit) {
        val end = SystemClock.elapsedRealtime() + 12_000; var last: AssertionError? = null
        do { try { check(); return } catch (error: AssertionError) { last = error }; SystemClock.sleep(60) }
        while (SystemClock.elapsedRealtime() < end)
        throw last ?: AssertionError("Timed out")
    }
    @Before fun setup() {
        val automation = instrumentation.uiAutomation
        assertEquals("LocalPhoneAgent_API35", automation.executeShellCommand("getprop ro.boot.qemu.avd_name").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText().trim() })
        automation.executeShellCommand("pm grant dev.localphone.agent android.permission.RECORD_AUDIO").close()
        previousSetting = graph.settings.get("stt_diagnostics")
        graph.settings.put("stt_diagnostics", "yes"); graph.settings.put("diagnostic_comparison_group", "synthetic-diagnostic-group")
        graph.settings.put("voice_onboarded", "yes"); graph.settings.put("voice_haptics", "no")
        graph.invocationDebug.clear(); graph.invocationDebug.awaitPersistence()
        graph.agentModelEnabledOverride = false
        graph.speechFactoryOverride = { FakeSpeech().also { sources += it } }
    }
    @After fun cleanup() {
        scenarios.asReversed().forEach { it.close() }; graph.speechFactoryOverride = null; graph.agentModelEnabledOverride = null
        graph.settings.put("stt_diagnostics", previousSetting); graph.settings.put("diagnostic_comparison_group", "")
        graph.invocationDebug.clear(); graph.invocationDebug.awaitPersistence()
    }
    private fun trace(id: String, origin: String = "MANUAL_MICROPHONE", speech: SpeechDiagnostic? = null) = InvocationTrace(
        sessionId = id, origin = origin, profile = "UserHandle{0}", interpretation = "RULE_BASED", times = emptyMap(),
        transitions = emptyList(), transcript = "private command", clarification = "private clarification", stt = null,
        agentLoad = null, modelInferenceMs = null, toolPlan = "private plan", policy = "private policy", execution = "private screen",
        disposition = "", result = "SUCCESS", error = "private error", audioFocus = "", duplicateInvocations = 0, totalMs = 1,
        speech = speech, modelResponse = ModelResponse(emptyList(), "private response", 1), modelEnabled = false,
        audit = InvocationAudit(enabled = true, comparisonGroup = "synthetic-diagnostic-group", snapshot = emptyMap(),
            events = listOf(InvocationEvent(1, 0, "TEST", "main", Gson().toJsonTree("private payload"))), droppedEvents = 0))
    private fun diagnostic(score: Float) = SpeechDiagnostic(SpeechRecognitionResult(listOf(SpeechHypothesis("회사로 안내해 줘", score, 0)),
        engine = "android-native-on-device", onDevice = true), ResolvedTranscript(emptyList(), "회사로 안내해 줘", false, score,
            listOf("TEST"), emptyList(), requiresClarification = true), SpeechContext(), 1)
    @Test fun historySurvivesReconstructionAndCheckpointsDoNotOverwriteAnotherCompletedCall() {
        repeat(36) { graph.invocationDebug.write(trace("call-$it").copy(invokedAt = 1000L + it)) }
        val latest = graph.invocationDebug.last
        graph.invocationDebug.checkpoint(trace("pending").copy(result = "IN_PROGRESS", invokedAt = 2000))
        repeat(3) { graph.invocationDebug.checkpoint(trace("pending").copy(result = "IN_PROGRESS", invokedAt = 2000)) }
        assertEquals(latest?.sessionId, graph.invocationDebug.last?.sessionId)
        graph.invocationDebug.awaitPersistence()
        val restored = InvocationDebugStore(graph.settings).readHistory()
        assertEquals(30, restored.size); assertEquals(30, restored.map { it.sessionId }.distinct().size)
        assertEquals("pending", restored.first().sessionId); assertEquals("call-35", restored[1].sessionId)
        assertEquals("private payload", restored[1].audit!!.events.single().payload!!.asString)
        assertFalse(context.getSharedPreferences("local_settings", 0).getString(InvocationDebugStore.HISTORY_KEY, "")!!.contains("private payload"))
    }
    @Test fun disabledDetailsRedactCommandsModelInputsAndAuditFromPersistence() {
        graph.settings.put("stt_diagnostics", "no")
        graph.invocationDebug.write(trace("private", speech = diagnostic(0f))); graph.invocationDebug.awaitPersistence()
        val stored = InvocationDebugStore(graph.settings).readHistory().single()
        assertEquals("", stored.transcript); assertNull(stored.speech); assertNull(stored.modelResponse); assertNull(stored.audit)
        assertFalse(Gson().toJson(stored).contains("private "))
    }
    @Test fun corruptedHistoryFallsBackToLegacyLastRecord() {
        graph.invocationDebug.write(trace("legacy")); graph.invocationDebug.awaitPersistence()
        graph.settings.put(InvocationDebugStore.HISTORY_KEY, "invalid-json")
        assertEquals("legacy", InvocationDebugStore(graph.settings).readHistory().single().sessionId)
    }
    @Test fun comparisonDistinguishesConfidenceEvenWhenLiteralTextIsIdentical() {
        val manual = trace("manual", speech = diagnostic(.2f))
        val automatic = trace("auto", "ANDROID_APP_LAUNCH", diagnostic(0f))
        val comparison = InvocationComparisons.compare(listOf(manual, automatic), "synthetic-diagnostic-group")
        assertEquals("PAIRED", comparison.status); assertEquals(true, comparison.sameLiteral)
        assertTrue(comparison.differences.any { it.field == "rawHypotheses" })
        assertTrue(comparison.note.contains("원인 확정이 아닙니다"))
        assertEquals("NEED_BOTH_ENTRIES", InvocationComparisons.compare(listOf(manual, automatic), "another-group").status)
    }
    @Test fun comparisonIncludesInterruptedCallsAndRawCallbacksThatNeverReachedResolver() {
        val central = trace("manual", speech = diagnostic(0f))
        val callback = InvocationEvent(1, 20, "ASR_FINAL", "main", Gson().toJsonTree(mapOf("ownerState" to "CANCELLED; current=false", "value" to diagnostic(0f).recognition)))
        val cancelled = trace("auto", "ANDROID_APP_LAUNCH").copy(result = "CANCELLED")
        val auto = cancelled.copy(audit = cancelled.audit!!.copy(events = listOf(callback)))
        val compared = InvocationComparisons.compare(listOf(central, auto), "synthetic-diagnostic-group")
        assertEquals("PAIRED", compared.status); assertEquals("auto", compared.automaticId)
        assertTrue(compared.differences.any { it.field == "resolution" })
        assertFalse(compared.differences.any { it.field == "rawHypotheses" })
        val incomplete = InvocationComparisons.compare(listOf(central, cancelled.copy(audit = cancelled.audit!!.copy(events = emptyList()))), "synthetic-diagnostic-group")
        assertEquals("INCOMPLETE_ASR", incomplete.status); assertNull(incomplete.sameLiteral)
        assertEquals("auto", incomplete.automaticId)
    }
    @Test fun bothEntryOwnersRetainRejectedZeroSpeechBeforeClarificationAndCancellation() {
        var activity: MainActivity? = null
        val manual = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        scenarios += manual; manual.onActivity { activity = it; it.findViewById<VoiceButton>(R.id.voice_button).performClick() }
        await { assertEquals(1, sources.size) }
        main { sources[0].listener.onDiagnostic("NATIVE_FINAL_BUNDLE", mapOf("confidenceValues" to listOf("0.0")))
            sources[0].listener.onRecognition(diagnostic(0f).recognition) }
        onView(withText("취소")).perform(click())
        await { assertEquals("LOW_CONFIDENCE", graph.invocationDebug.last?.result) }
        val manualId = graph.invocationDebug.last!!.sessionId
        var entry: VoiceInvocationActivity? = null
        val automatic = ActivityScenario.launch<VoiceInvocationActivity>(Intent(context, VoiceInvocationActivity::class.java))
        scenarios += automatic; automatic.onActivity { entry = it }
        await { assertEquals(2, sources.size) }
        main { sources[1].listener.onDiagnostic("NATIVE_FINAL_BUNDLE", mapOf("confidenceValues" to listOf("0.0")))
            sources[1].listener.onRecognition(diagnostic(0f).recognition) }
        await { assertEquals(3, sources.size) }
        main { entry!!.coordinator!!.cancel() }
        val calls = graph.invocationDebug.readHistory()
        val central = calls.single { it.sessionId == manualId }
        val auto = calls.single { it.origin == "ANDROID_APP_LAUNCH" }
        for (record in listOf(central, auto)) {
            assertEquals(0f, record.speech!!.recognition.hypotheses.single().acousticConfidence!!, 0f)
            assertTrue(record.speech!!.resolution.requiresClarification)
            assertFalse(record.modelEnabled); assertNull(record.modelResponse)
            val names = record.audit!!.events.map { it.name }
            assertTrue(names.containsAll(listOf("SPEECH_CONTEXT", "MIC_READY", "ASR_FINAL", "RESOLVER_OUTPUT", "END")))
            assertFalse(names.contains("MODEL_REQUEST"))
        }
        assertTrue(central.audit!!.events.any { it.name == "SCREEN_CLARIFICATION_REQUESTED" })
        assertTrue(auto.audit!!.events.any { it.name == "VOICE_CLARIFICATION_REQUESTED" })
        assertEquals("PAIRED", InvocationComparisons.compare(calls, "synthetic-diagnostic-group").status)
        graph.invocationDebug.awaitPersistence()
        assertEquals(2, InvocationDebugStore(graph.settings).readHistory().size)
    }
    @Test fun cancellationKeepsLateFinalInSameSessionWithoutExecutingIt() {
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)); scenarios += scenario
        scenario.onActivity { it.findViewById<VoiceButton>(R.id.voice_button).performClick() }
        await { assertEquals(1, sources.size) }
        scenario.onActivity { it.findViewById<VoiceButton>(R.id.voice_button).performClick() }
        val id = graph.invocationDebug.last!!.sessionId
        main { sources.single().listener.onRecognition(diagnostic(0f).recognition) }
        val record = graph.invocationDebug.readHistory().single { it.sessionId == id }
        assertEquals("CANCELLED", record.result); assertNull(record.speech)
        assertTrue(record.audit!!.events.any { it.name == "ASR_FINAL" && it.payload!!.toString().contains("current=false") })
        assertFalse(record.audit!!.events.any { it.name == "MODEL_REQUEST" })
    }
    @Test fun clearingHistoryCannotBeUndoneByLateCaptureAndHostCallbacks() {
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)); scenarios += scenario
        scenario.onActivity { it.findViewById<VoiceButton>(R.id.voice_button).performClick() }
        await { assertEquals(1, sources.size) }
        scenario.onActivity { it.findViewById<VoiceButton>(R.id.voice_button).performClick() }
        assertEquals("CANCELLED", graph.invocationDebug.last!!.result)
        graph.invocationDebug.clear()
        main { sources.single().listener.onRecognition(diagnostic(0f).recognition) }
        scenario.close(); scenarios.remove(scenario)
        graph.invocationDebug.awaitPersistence()
        assertNull(graph.invocationDebug.last); assertTrue(graph.invocationDebug.readHistory().isEmpty())
        assertTrue(InvocationDebugStore(graph.settings).readHistory().isEmpty())
    }
    @Test fun diagnosticUiStartsComparisonAndCopiesBothRecordsWithoutBorrowingUnrelatedSpeech() {
        graph.settings.put("stt_diagnostics", "no")
        val scenario = ActivityScenario.launch<InvocationDebugActivity>(Intent(context, InvocationDebugActivity::class.java)); scenarios += scenario
        scenario.onActivity { it.findViewById<Button>(R.id.diagnostic_start_comparison).performClick() }
        assertEquals("yes", graph.settings.get("stt_diagnostics"))
        val group = graph.settings.get("diagnostic_comparison_group"); assertTrue(group.isNotBlank())
        for ((id, origin) in listOf("manual" to "MANUAL_MICROPHONE", "auto" to "ANDROID_APP_LAUNCH")) {
            val call = trace(id, origin, diagnostic(0f))
            graph.invocationDebug.write(call.copy(audit = call.audit!!.copy(comparisonGroup = group)))
        }
        scenario.onActivity { activity ->
            activity.findViewById<Button>(R.id.diagnostic_refresh).performClick()
            activity.findViewById<Button>(R.id.diagnostic_copy_comparison).performClick()
            val data = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(data.contains("PAIRED")); assertTrue(data.contains("manual")); assertTrue(data.contains("auto"))
        }
        graph.invocationDebug.write(trace("text", "TEXT_INPUT").copy(invokedAt = System.currentTimeMillis() + 100))
        scenario.onActivity { activity ->
            activity.findViewById<Button>(R.id.diagnostic_refresh).performClick()
            activity.findViewById<android.widget.Spinner>(R.id.diagnostic_history).setSelection(0)
        }
        await { scenario.onActivity { activity ->
            val text = activity.findViewById<TextView>(R.id.diagnostic_details).text.toString()
            assertTrue(text.contains("TEXT_INPUT")); assertFalse(text.contains("RAW #"))
        } }
    }
}
