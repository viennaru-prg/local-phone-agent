package dev.localphone.agent

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.localphone.agent.runtime.*
import dev.localphone.agent.ui.*
import org.hamcrest.Matchers.containsString
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var activity: MainActivity
    private lateinit var fake: FakeSpeech
    private val clock = "com.google.android.deskclock"
    private var intentsReady = false
    private var dedicated = false
    private var animationScale = "1"

    private class FakeSpeech : SpeechInput {
        lateinit var listener: SpeechInput.Listener
        var cancelled = false
        override fun start(listener: SpeechInput.Listener) { this.listener = listener; listener.onListening() }
        override fun cancel() { cancelled = true }
    }
    @Before fun before() {
        dedicated = device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "LocalPhoneAgent_API35"
        Assume.assumeTrue("Only the task-owned AVD is allowed", dedicated)
        animationScale = device.executeShellCommand("settings get global animator_duration_scale").trim()
        device.executeShellCommand("settings put global animator_duration_scale 1")
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        fake = FakeSpeech()
        graph.speechFactoryOverride = { fake }
        Intents.init(); intentsReady = true
    }
    @After fun after() {
        if (::activity.isInitialized) instrumentation.runOnMainSync {
            val button = activity.findViewById<VoiceButton>(R.id.voice_button)
            if (button?.phase in listOf(VoicePhase.LISTENING, VoicePhase.PREPARING)) button?.performClick()
        }
        scenario?.close(); graph.speechFactoryOverride = null
        if (intentsReady) Intents.release()
        if (dedicated) {
            device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            if (animationScale.matches(Regex("[0-9.]+"))) device.executeShellCommand("settings put global animator_duration_scale $animationScale")
        }
    }
    private fun launch() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        scenario!!.onActivity { activity = it }
    }
    // Infinite native listening animation deliberately never becomes Espresso-idle.
    // Use actual View clicks on the UI thread and read that View without waiting for animation completion.
    private fun pressVoice() = instrumentation.runOnMainSync { activity.findViewById<VoiceButton>(R.id.voice_button).performClick() }
    private fun inActivity(assertion: (MainActivity) -> Unit) = instrumentation.runOnMainSync { assertion(activity) }
    private fun heading(expected: String) = inActivity { assertEquals(expected, it.findViewById<android.widget.TextView>(R.id.voice_heading).text.toString()) }
    private fun statusIncludes(expected: String) = inActivity { assertTrue(it.findViewById<android.widget.TextView>(R.id.command_status).text.toString().contains(expected)) }
    private fun await(timeout: Long = 10_000, assertion: () -> Unit) {
        val end = SystemClock.elapsedRealtime() + timeout
        var last: Throwable? = null
        do {
            try { assertion(); return } catch (error: AssertionError) { last = error }
            catch (error: androidx.test.espresso.NoMatchingViewException) { last = error }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < end)
        throw last ?: AssertionError("Timed out")
    }
    private fun feed(action: (SpeechInput.Listener) -> Unit) { instrumentation.runOnMainSync { action(fake.listener) } }
    private fun noDispatch() = assertTrue(Intents.getIntents().none {
        (it.action == Intent.ACTION_MAIN && it.component?.packageName !in listOf(context.packageName, context.packageName + ".test"))
            || it.action in listOf(android.provider.AlarmClock.ACTION_SET_ALARM, android.provider.AlarmClock.ACTION_SET_TIMER)
            || it.data?.scheme == "nmap" || it.action?.startsWith("android.settings.") == true
    })
    private fun screenshot(name: String) {
        inActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        SystemClock.sleep(500)
        val directory = File(context.getExternalFilesDir(null), "emulator-evidence").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun homeHasVoiceButtonAndKeepsPlaceManagementInSettings() {
        launch()
        onView(withId(R.id.voice_button)).check(matches(isDisplayed()))
        onView(withText("눌러서 말해주세요")).check(matches(isDisplayed()))
        onView(withId(R.id.command_input)).check(doesNotExist())
        onView(withText("집 설정")).check(doesNotExist())
        onView(withText("내 장소")).check(doesNotExist())
        scenario!!.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        screenshot("09-simple-voice-home")
        onView(withId(R.id.agent_settings)).perform(click())
        onView(withText("집 설정")).check(matches(isDisplayed()))
        onView(withId(R.id.agent_back)).perform(click())
        onView(withId(R.id.voice_button)).check(matches(isDisplayed()))
        noDispatch()
    }
    @Test fun listeningAnimatesAndPartialSpeechNeverExecutes() {
        launch(); pressVoice()
        feed { it.onPartial("시계 앱 열어줘"); it.onLevel(0.7f) }
        heading("듣고 있어요")
        inActivity { assertEquals("시계 앱 열어줘", it.findViewById<android.widget.TextView>(R.id.voice_transcript).text.toString()) }
        var position = 0f
        inActivity {
            val button = it.findViewById<VoiceButton>(R.id.voice_button)
            assertEquals(VoicePhase.LISTENING, button.phase); assertTrue(button.isAnimating)
            position = button.animationPosition
        }
        SystemClock.sleep(250)
        inActivity { assertNotEquals(position, it.findViewById<VoiceButton>(R.id.voice_button).animationPosition) }
        screenshot("10-listening-animation")
        // Keep this synthetic state visible long enough for optional emulator video capture.
        SystemClock.sleep(2_000)
        pressVoice()
        assertTrue(fake.cancelled)
        inActivity { assertFalse(it.findViewById<VoiceButton>(R.id.voice_button).isAnimating) }
        noDispatch()
    }
    @Test fun finalSpeechAutomaticallyOpensClockOnlyOnce() {
        launch(); pressVoice()
        feed { it.onFinal("시계 앱 열어줘"); it.onFinal("시계 앱 열어줘") }
        await { assertEquals(clock, device.currentPackageName) }
        assertEquals(1, Intents.getIntents().count { it.action == Intent.ACTION_MAIN && it.component?.packageName == clock })
        assertTrue(fake.cancelled)
    }
    @Test fun cancelledSpeechCannotExecuteALateFinalResult() {
        launch(); pressVoice(); pressVoice()
        feed { it.onFinal("시계 앱 열어줘") }
        statusIncludes("취소했어요")
        noDispatch()
    }
    @Test fun leavingTheAppCancelsListeningAndRejectsLateSpeech() {
        launch(); pressVoice()
        device.pressHome()
        await { assertTrue(fake.cancelled) }
        assertTrue(fake.cancelled)
        feed { it.onFinal("시계 앱 열어줘") }
        noDispatch()
        heading("눌러서 말해주세요")
    }
    @Test fun speechErrorAndEmptyResultDoNotExecute() {
        launch(); pressVoice()
        feed { it.onError("잘 듣지 못했어요.") }
        statusIncludes("잘 듣지 못했어요.")
        noDispatch()
        fake = FakeSpeech()
        pressVoice()
        feed { it.onFinal("  ") }
        statusIncludes("다시 눌러")
        noDispatch()
    }
    @Test fun realKoreanOfflineDecoderCreatesRequestedAlarmThroughActualClock() {
        val originalAirplane = device.executeShellCommand("settings get global airplane_mode_on").trim()
        device.executeShellCommand("cmd connectivity airplane-mode enable")
        try {
            assertEquals("1", device.executeShellCommand("settings get global airplane_mode_on").trim())
            val pcm = pcmFromSyntheticWave()
            val directory = File(context.getExternalFilesDir(null), "emulator-evidence").apply { mkdirs() }
            graph.speechFactoryOverride = {
                val local = LocalSpeechInput(graph.speechModel) { ByteArrayPcm(pcm) }
                object : SpeechInput by local {
                    override fun start(listener: SpeechInput.Listener) = local.start(object : SpeechInput.Listener by listener {
                        override fun onFinal(text: String) {
                            File(directory, "offline-speech-result.txt").writeText(
                                "Synthetic Korean WAV → real sherpa-onnx Android decoder\nAirplane mode: enabled\nRecognized: $text\nPhysical microphone speech: not exercised by this test\n")
                            listener.onFinal(text)
                        }
                        override fun onError(message: String) {
                            File(directory, "offline-speech-result.txt").writeText("Decoder error: $message\n")
                            listener.onError(message)
                        }
                    })
                }
            }
            launch(); pressVoice()
            await(45_000) {
                val diagnostic = File(directory, "offline-speech-result.txt").takeIf { it.exists() }?.readText().orEmpty()
                assertEquals(diagnostic, clock, device.currentPackageName)
            }
            var recognized = ""
            inActivity { recognized = it.findViewById<android.widget.TextView>(R.id.voice_transcript).text.toString() }
            File(directory, "offline-speech-result.txt").writeText(
                "Synthetic Korean WAV → real sherpa-onnx Android decoder → actual Clock app\nAirplane mode: enabled\nRecognized: $recognized\nPhysical microphone speech: not exercised by this test\n")
            assertTrue(recognized.contains("알람"))
            val request = Intents.getIntents().single { it.action == android.provider.AlarmClock.ACTION_SET_ALARM }
            assertEquals(7, request.getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, -1))
            assertEquals(0, request.getIntExtra(android.provider.AlarmClock.EXTRA_MINUTES, -1))
            assertTrue(device.takeScreenshot(File(directory, "11-offline-voice-alarm.png")))
        } finally {
            if (originalAirplane != "1") device.executeShellCommand("cmd connectivity airplane-mode disable")
            device.executeShellCommand("pm clear $clock") // Dedicated AVD: remove the synthetic alarm.
        }
    }
    @Test fun realMicrophoneSessionStartsAndStopsWithoutDispatchingSilence() {
        graph.speechFactoryOverride = { LocalSpeechInput(graph.speechModel) }
        launch(); pressVoice()
        await(90_000) { heading("듣고 있어요") }
        SystemClock.sleep(300)
        pressVoice(); heading("눌러서 말해주세요")
        noDispatch()
    }
    private class ByteArrayPcm(private val bytes: ByteArray) : PcmSource {
        private var offset = 0
        private var stopped = false
        override fun start() = Unit
        override fun read(buffer: ByteArray): Int {
            if (stopped || offset >= bytes.size) return -1
            val count = minOf(buffer.size, bytes.size - offset)
            bytes.copyInto(buffer, 0, offset, offset + count); offset += count
            return count
        }
        override fun stop() { stopped = true }
        override fun close() = stop()
    }
    private fun pcmFromSyntheticWave(): ByteArray {
        val wave = instrumentation.context.assets.open("synthetic-alarm-ko.wav").use { it.readBytes() }
        assertEquals("RIFF", String(wave, 0, 4, Charsets.US_ASCII)); assertEquals("WAVE", String(wave, 8, 4, Charsets.US_ASCII))
        var offset = 12
        var validFormat = false
        while (offset + 8 <= wave.size) {
            val name = String(wave, offset, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(wave, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val data = offset + 8
            check(length >= 0 && data + length <= wave.size)
            if (name == "fmt ") {
                val format = ByteBuffer.wrap(wave, data, length).slice().order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(1, format.short.toInt()); assertEquals(1, format.short.toInt()); assertEquals(16_000, format.int)
                format.int; format.short; assertEquals(16, format.short.toInt()); validFormat = true
            }
            if (name == "data") { assertTrue(validFormat); return wave.copyOfRange(data, data + length) }
            offset = data + length + length % 2
        }
        error("Missing PCM data")
    }
}
