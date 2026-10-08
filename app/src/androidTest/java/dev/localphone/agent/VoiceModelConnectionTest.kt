package dev.localphone.agent

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.localphone.agent.runtime.*
import dev.localphone.agent.ui.VoiceButton
import dev.localphone.core.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Full production voice entrypoints + REAL local PCM decoder + REAL selected native Qwen.
 * Synthetic audio on the owned AVD, never a human/Samsung microphone accuracy claim. */
class VoiceModelConnectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private val device = UiDevice.getInstance(instrumentation)
    private var home: ActivityScenario<MainActivity>? = null
    private var entry: VoiceInvocationActivity? = null
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(timeout: Long = 120_000, check: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + timeout; var error: AssertionError? = null
        do { try { check(); return } catch (failure: AssertionError) { error = failure }; SystemClock.sleep(100) }
        while (SystemClock.elapsedRealtime() < deadline)
        throw error ?: AssertionError("Timed out")
    }
    @Before fun setup() {
        assertEquals("LocalPhoneAgent_API35", device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        graph.agentModelEnabledOverride = null // Production default, not a test opt-in.
        assertTrue(graph.useAgentModel)
        graph.settings.put("voice_onboarded", "yes")
        graph.settings.put("voice_haptics", "no")
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        runBlocking { assertTrue(graph.planner.select(LocalModelId.QWEN3).success); graph.planner.close() }
        val pcm = pcm()
        graph.speechFactoryOverride = { LocalSpeechInput(graph.speechModel) { object : PcmSource {
            var offset = 0; @Volatile var stopped = false
            override fun start() = Unit
            override fun read(buffer: ByteArray): Int {
                if (stopped || offset >= pcm.size) return -1
                val count = minOf(buffer.size, pcm.size - offset)
                pcm.copyInto(buffer, 0, offset, offset + count); offset += count; return count
            }
            override fun stop() { stopped = true }
            override fun close() = stop()
        } } }
    }
    @After fun cleanup() {
        main { entry?.coordinator?.cancel(); entry?.finish() }
        home?.close(); graph.speechFactoryOverride = null
        runBlocking { graph.planner.close() }
        device.executeShellCommand("pm clear com.google.android.deskclock") // Owned AVD synthetic alarm only.
    }
    private fun pcm(): ByteArray {
        val wave = instrumentation.context.assets.open("synthetic-alarm-ko.wav").use { it.readBytes() }
        var at = 12
        while (at + 8 <= wave.size) {
            val size = ByteBuffer.wrap(wave, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(size >= 0 && at + 8 + size <= wave.size)
            if (String(wave, at, 4, Charsets.US_ASCII) == "data") return wave.copyOfRange(at + 8, at + 8 + size)
            at += 8 + size + size % 2
        }
        error("No PCM fixture")
    }
    private fun record(name: String, started: Long) {
        val response = checkNotNull(graph.planner.lastResponse)
        assertEquals(listOf(Action.SetAlarm(7, 0)), ToolPlanDecoder.decode(response.calls).actions)
        assertEquals("qwen3", graph.planner.loadedId)
        val times = graph.invocationDebug.last!!.times
        assertTrue(times.getValue("MODEL_PREPARE_STARTED") >= times.getValue("T2"))
        val value = mapOf("scope" to "owned API35 x86_64 AVD, synthetic PCM, real local decoder and native Qwen",
            "version" to BuildConfig.VERSION_NAME, "elapsed_ms" to SystemClock.elapsedRealtime() - started,
            "selected" to graph.planner.selected.key, "load" to graph.planner.lastLoad,
            "response" to response, "recognition" to graph.speechDiagnostics.last,
            "trace" to graph.invocationDebug.last, "foreground" to device.currentPackageName)
        val directory = File(context.getExternalFilesDir(null), "voice-model-evidence").apply { mkdirs() }
        File(directory, "$name.json").writeText(Gson().toJson(value))
    }
    @Test fun manualMicUsesSelectedNativeQwenThroughActualClock() {
        home = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        val started = SystemClock.elapsedRealtime()
        home!!.onActivity { it.findViewById<VoiceButton>(R.id.voice_button).performClick() }
        await { assertEquals("com.google.android.deskclock", device.currentPackageName) }
        await { assertEquals("MANUAL_MICROPHONE", graph.invocationDebug.last?.origin)
            assertEquals("MODEL_INFERENCE:qwen3", graph.invocationDebug.last?.interpretation) }
        record("manual-mic", started)
    }
    @Test fun launcherVoiceUsesSelectedNativeQwenThroughActualClock() {
        val previous = graph.invocationDebug.last?.sessionId
        val started = SystemClock.elapsedRealtime()
        main { context.startActivity(checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))) }
        await(10_000) { main { entry = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<VoiceInvocationActivity>().singleOrNull() }; assertNotNull(entry) }
        await {
            val trace = graph.invocationDebug.last
            assertNotEquals(previous, trace?.sessionId)
            assertEquals("SUCCESS", trace?.result)
            assertEquals("MODEL_INFERENCE:qwen3", trace?.interpretation)
        }
        await { assertEquals("com.google.android.deskclock", device.currentPackageName) }
        record("launcher-mic", started)
    }
    @Test fun coldAutomaticEntryWaitsForMicrophoneReadyBeforePreparingModel() {
        var listener: SpeechInput.Listener? = null
        graph.speechFactoryOverride = { object : SpeechInput {
            override fun start(value: SpeechInput.Listener) { listener = value }
            override fun cancel() = Unit
        } }
        main { context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!) }
        await(10_000) { main { entry = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<VoiceInvocationActivity>().singleOrNull() }; assertNotNull(listener) }
        SystemClock.sleep(300)
        assertEquals(InvocationState.STARTING, entry?.coordinator?.state)
        assertEquals(ModelRuntimeState.INSTALLED, graph.planner.status.value.getValue(LocalModelId.QWEN3).state)
        assertNull(graph.planner.loadedId)
        main { listener!!.onListening() }
        await(10_000) { assertEquals(InvocationState.LISTENING, entry?.coordinator?.state)
            assertNotEquals(ModelRuntimeState.INSTALLED, graph.planner.status.value.getValue(LocalModelId.QWEN3).state) }
        main { entry!!.coordinator!!.cancel() }
        assertTrue(graph.invocationDebug.last!!.times.getValue("MODEL_PREPARE_STARTED") >= graph.invocationDebug.last!!.times.getValue("T2"))
    }
}
