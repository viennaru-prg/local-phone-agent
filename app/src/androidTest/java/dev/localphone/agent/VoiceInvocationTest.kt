package dev.localphone.agent

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.localphone.agent.runtime.*
import dev.localphone.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceInvocationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private lateinit var fake: FakeSpeech
    private var entry: VoiceInvocationActivity? = null
    private var session: MediaSession? = null
    private val plays = AtomicInteger()
    private val pauses = AtomicInteger()
    private val skips = AtomicInteger()
    private val directory get() = File(context.getExternalFilesDir(null), "emulator-evidence").apply { mkdirs() }
    private class FakeSpeech : SpeechInput {
        lateinit var listener: SpeechInput.Listener
        val starts = AtomicInteger()
        @Volatile var cancelled = false
        override fun start(listener: SpeechInput.Listener) { this.listener = listener; starts.incrementAndGet(); listener.onListening() }
        override fun cancel() { cancelled = true }
    }
    @Before fun before() {
        Assume.assumeTrue("Only task-owned AVD", device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "LocalPhoneAgent_API35")
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        graph.settings.put("voice_onboarded", "yes"); graph.settings.put("use_functiongemma", "")
        graph.settings.put("verified_native_speech", "")
        graph.settings.put("stt_focus_mode", "")
        graph.settings.put("music_package", context.packageName)
        graph.settings.put("voice_haptics", "no")
        fake = FakeSpeech(); graph.speechFactoryOverride = { fake }
        graph.focusFactoryOverride = null
        runBlocking { graph.places.all().forEach { graph.places.delete(it.id) } }
        Intents.init()
    }
    @After fun after() {
        instrumentation.runOnMainSync { entry?.coordinator?.cancel(); entry?.finishAndRemoveTask() }
        session?.release(); graph.speechFactoryOverride = null; graph.focusFactoryOverride = null
        graph.settings.put("use_functiongemma", ""); graph.settings.put("voice_haptics", "")
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        Intents.release()
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(timeout: Long = 12000, block: () -> Unit) {
        val end = SystemClock.elapsedRealtime() + timeout; var last: AssertionError? = null
        do { try { block(); return } catch (error: AssertionError) { last = error }; SystemClock.sleep(80) }
        while (SystemClock.elapsedRealtime() < end)
        throw last ?: AssertionError("Timed out")
    }
    private fun openMap() {
        main { context.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("nmap://navigation?dlat=36.123456&dlng=128.123456&dname=PreviousMap&appname=${context.packageName}"))
            .setPackage(NaverLinks.PACKAGE).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        await { assertEquals(NaverLinks.PACKAGE, device.currentPackageName)
            assertNotNull(device.findObject(androidx.test.uiautomator.By.text("목적지: PreviousMap"))) }
    }
    private fun launch(waitForListening: Boolean = true) {
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        assertEquals(VoiceInvocationActivity::class.java.name, intent.component?.className)
        main { context.startActivity(intent) }
        await {
            main { entry = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<VoiceInvocationActivity>().singleOrNull() }
            assertNotNull(entry)
            if (waitForListening) main { assertEquals(InvocationState.LISTENING, entry?.coordinator?.state) }
        }
    }
    private fun feed(block: (SpeechInput.Listener) -> Unit) = main { block(fake.listener) }
    private fun result(expected: InvocationState) {
        await {
            assertEquals(expected.name, graph.invocationDebug.last?.result)
            assertEquals(entry?.coordinator?.sessionId, graph.invocationDebug.last?.sessionId)
        }
    }
    private fun media() {
        device.executeShellCommand("cmd notification allow_listener ${context.packageName}/dev.localphone.agent.runtime.AgentNotificationListener")
        session = MediaSession(context, "InvocationTestSyntheticMusic").also { media ->
            media.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { plays.incrementAndGet(); playback(PlaybackState.STATE_PLAYING) }
                override fun onPause() { pauses.incrementAndGet(); playback(PlaybackState.STATE_PAUSED) }
                override fun onSkipToNext() { skips.incrementAndGet() }
            }, Handler(Looper.getMainLooper()))
            playback(PlaybackState.STATE_PLAYING); media.isActive = true
        }
        // Set state after session assignment, as the first also call precedes that assignment.
        playback(PlaybackState.STATE_PLAYING)
    }
    private fun playback(state: Int) { session?.setPlaybackState(PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
        .setState(state, 0, if (state == PlaybackState.STATE_PLAYING) 1f else 0f).build()) }
    private fun screenshot(name: String) {
        main { entry?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        SystemClock.sleep(300)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    @Test fun launcherAutoStartsSmallOverlayWithoutTouchAndReturnsToMapAfterNext() {
        media(); openMap(); launch()
        assertEquals(1, fake.starts.get())
        main {
            assertTrue(entry!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val text = entry!!.findViewById<TextView>(R.id.invocation_status)
            assertEquals("듣는 중…", text.text.toString())
            assertNull(entry!!.findViewById<android.view.View>(R.id.command_input))
            assertTrue(entry!!.window.attributes.width < device.displayWidth)
        }
        screenshot("12-voice-invocation-over-map")
        feed { it.onFinal("다음 곡 틀어") }
        result(InvocationState.SUCCESS)
        await { assertEquals(1, skips.get()); assertEquals(NaverLinks.PACKAGE, device.currentPackageName) }
        assertNotNull(device.findObject(androidx.test.uiautomator.By.text("목적지: PreviousMap")))
        assertEquals(0, plays.get()); assertEquals(0, pauses.get())
        assertEquals("BACKGROUND_ACTION", graph.invocationDebug.last?.disposition)
        assertTrue(fake.cancelled)
        assertFalse(device.executeShellCommand("dumpsys activity recents").contains("dev.localphone.agent/.VoiceInvocationActivity"))
        File(directory, "invocation-next-trace.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(graph.invocationDebug.last))
    }
    @Test fun pauseWhileMapWasFrontDoesNotResumeAtSessionEnd() {
        media(); openMap(); launch(); feed { it.onFinal("노래 멈춰") }
        result(InvocationState.SUCCESS)
        await { assertEquals(1, pauses.get()); assertEquals(NaverLinks.PACKAGE, device.currentPackageName) }
        assertNotNull(device.findObject(androidx.test.uiautomator.By.text("목적지: PreviousMap")))
        SystemClock.sleep(300)
        assertEquals(0, plays.get()); assertEquals(PlaybackState.STATE_PAUSED, session!!.controller.playbackState?.state)
    }
    @Test fun structuredPartialsNeverDispatchAndFinalKeepsEveryHypothesis() {
        media(); openMap(); launch()
        val speech = SpeechRecognitionResult(listOf(SpeechHypothesis("다음 곡", .90f, 0),
            SpeechHypothesis("다음곡", .88f, 1)), engine = "INSTRUMENTATION_NBEST", onDevice = true)
        feed { it.onRecognition(speech.copy(finalResult = false)) }
        SystemClock.sleep(150)
        assertEquals(0, skips.get()); assertEquals(InvocationState.LISTENING, entry!!.coordinator!!.state)
        feed { it.onRecognition(speech) }
        result(InvocationState.SUCCESS); await { assertEquals(1, skips.get()) }
        assertEquals(2, graph.invocationDebug.last!!.speech!!.recognition.hypotheses.size)
        assertEquals(.90f, graph.invocationDebug.last!!.speech!!.resolution.confidence!!, .0001f)
    }
    @Test fun competingMediaHypothesesNeedOneVoiceChoiceBeforeAnyAction() {
        media(); openMap(); launch()
        feed { it.onRecognition(SpeechRecognitionResult(listOf(SpeechHypothesis("노래 멈춰", .70f, 0),
            SpeechHypothesis("노래 틀어", .68f, 1)), engine = "INSTRUMENTATION_NBEST", onDevice = true)) }
        await { assertEquals(2, fake.starts.get()); assertEquals(InvocationState.LISTENING, entry!!.coordinator!!.state) }
        assertEquals(0, pauses.get()); assertEquals(0, plays.get())
        feed { it.onRecognition(SpeechRecognitionResult(listOf(SpeechHypothesis("노래 멈춰", .95f, 0)),
            engine = "INSTRUMENTATION_NBEST", onDevice = true)) }
        result(InvocationState.SUCCESS); await { assertEquals(1, pauses.get()) }
        assertEquals(0, plays.get()); assertEquals(2, fake.starts.get())
    }
    @Test fun navigationWithoutUiCapabilityReportsUnconfirmedCompletionAndKeepsMapFront() {
        media()
        runBlocking { graph.places.saveConfirmed(UserPlace("home", "집", PlaceSlots.aliases.getValue("home"),
            Coordinates(36.123456, 128.123456), "합성 테스트 주소", "TEST", PlaceSource.MANUAL, 1, 1)) }
        openMap(); launch(); feed { it.onFinal("집으로 네비 찍고 노래 재생해줘") }
        result(InvocationState.PERMISSION_REQUIRED)
        await { assertEquals(0, plays.get()); assertEquals(NaverLinks.PACKAGE, device.currentPackageName) }
        val target = Intents.getIntents().single { it.data?.scheme == "nmap" && it.data?.getQueryParameter("dname") == "집" }
        assertTrue(target.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("FOREGROUND_NAVIGATION", graph.invocationDebug.last?.disposition)
        await { assertNotNull(device.findObject(androidx.test.uiautomator.By.text("목적지: 집"))) }
        File(directory, "invocation-navigation-trace.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(graph.invocationDebug.last))
    }
    @Test fun duplicateLauncherInvocationsAndDuplicateFinalHaveOneEffect() {
        media(); openMap(); launch()
        repeat(2) { device.executeShellCommand("am start -n ${context.packageName}/.VoiceInvocationActivity") }
        main { assertEquals(InvocationState.LISTENING, entry!!.coordinator!!.state) }
        assertEquals(1, fake.starts.get())
        feed { it.onFinal("다음 곡"); it.onFinal("다음 곡") }
        result(InvocationState.SUCCESS); await { assertEquals(1, skips.get()) }
        assertEquals(2, graph.invocationDebug.last?.duplicateInvocations)
    }
    @Test fun backCancelsImmediatelyAndLateFinalCannotSkip() {
        media(); openMap(); launch(); device.pressBack()
        result(InvocationState.CANCELLED); assertTrue(fake.cancelled)
        feed { it.onFinal("다음 곡") }; SystemClock.sleep(250)
        assertEquals(0, skips.get()); await { assertEquals(NaverLinks.PACKAGE, device.currentPackageName) }
    }
    @Test fun cancelButtonClosesWithoutOpeningTheHome() {
        openMap(); launch(); main { entry!!.findViewById<android.view.View>(R.id.invocation_cancel).performClick() }
        result(InvocationState.CANCELLED); assertTrue(fake.cancelled)
        await { assertEquals(NaverLinks.PACKAGE, device.currentPackageName) }
    }
    @Test fun silenceAndDecoderFailureHaveDifferentTerminalStates() {
        openMap(); launch()
        feed { it.onFailure(SpeechFailure(SpeechError.NO_SPEECH, "음성 없음")) }; result(InvocationState.NO_SPEECH)
        await { assertEquals(NaverLinks.PACKAGE, device.currentPackageName) }
        fake = FakeSpeech(); entry = null; launch()
        feed { it.onFailure(SpeechFailure(SpeechError.STT_FAILED, "인식 실패")) }; result(InvocationState.STT_FAILED)
    }
    @Test fun ambiguousPlaceGetsOnlyOneVoiceClarificationWithoutGuessing() {
        runBlocking {
            for ((id, name) in listOf("a" to "수원역점", "b" to "시청점")) graph.places.saveConfirmed(
                UserPlace(id, name, listOf("단골"), Coordinates(36.2, 128.2), "합성", "TEST", PlaceSource.MANUAL, 1, 1))
        }
        openMap(); launch(); feed { it.onFinal("단골 가자") }
        await { assertEquals(2, fake.starts.get()) }
        assertEquals(1, Intents.getIntents().count { it.data?.scheme == "nmap" }) // Only the previous map.
        feed { it.onFinal("수원역점") }; result(InvocationState.PERMISSION_REQUIRED)
        await { assertEquals(NaverLinks.PACKAGE, device.currentPackageName) }
        assertEquals("수원역점", graph.invocationDebug.last?.clarification)
    }
    @Test fun missingAgentModelDoesNotBlockAnAvailableStructuredMediaFastPath() {
        Assume.assumeFalse(graph.modelFile.exists())
        graph.settings.put("use_functiongemma", "yes")
        media()
        openMap(); launch(); feed { it.onFinal("다음 곡") }
        result(InvocationState.SUCCESS)
        await { assertEquals(1, skips.get()) }
        assertEquals("DETERMINISTIC_GOAL_FAST_PATH", graph.invocationDebug.last?.interpretation)
    }
    @Test fun focusDenialStopsBeforeMicrophoneStarts() {
        graph.focusFactoryOverride = { object : CaptureFocus {
            override fun acquire(onLost: () -> Unit) = false
            override fun release() = Unit
        } }
        openMap(); launch(waitForListening = false); result(InvocationState.EXECUTION_FAILED)
        assertEquals(0, fake.starts.get()); assertEquals("DENIED", graph.invocationDebug.last?.audioFocus)
    }
    @Test fun firstLaunchOnboardsOnceThenStartsWithoutAnotherMicClick() {
        graph.settings.put("voice_onboarded", "")
        openMap()
        main { context.startActivity(checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))) }
        var setup: VoiceOnboardingActivity? = null
        await {
            main { setup = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<VoiceOnboardingActivity>().singleOrNull() }
            assertNotNull(setup)
        }
        assertEquals(0, fake.starts.get())
        main { setup!!.findViewById<android.view.View>(R.id.voice_onboarding_start).performClick() }
        await {
            main { entry = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<VoiceInvocationActivity>().singleOrNull() }
            assertEquals(1, fake.starts.get())
        }
        assertEquals("yes", graph.settings.get("voice_onboarded"))
        main { entry!!.coordinator!!.cancel() }; result(InvocationState.CANCELLED)
        await { assertEquals(NaverLinks.PACKAGE, device.currentPackageName) }
        fake = FakeSpeech(); entry = null; launch(); assertEquals(1, fake.starts.get())
    }
    @Test fun screenOffLaunchRequiresAuthAndNeverStartsMic() {
        openMap(); device.sleep()
        assertFalse(ProfileScope(context).canAct())
        val previous = graph.invocationDebug.last?.sessionId
        main { context.startActivity(checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))) }
        await {
            assertNotEquals(previous, graph.invocationDebug.last?.sessionId)
            assertEquals(InvocationState.AUTH_REQUIRED.name, graph.invocationDebug.last?.result)
        }
        assertEquals(0, fake.starts.get())
        File(directory, "invocation-screen-off-trace.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(graph.invocationDebug.last))
        // Return test-owned unsecured AVD to its initial interactive state.
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
    }
    @Test fun actualMicrophoneSilenceEndsWithoutAnyToolRequest() {
        val cleared = CountDownLatch(1); graph.speechModel.releaseForTest { cleared.countDown() }
        assertTrue(cleared.await(15, TimeUnit.SECONDS))
        // The dedicated AVD has no Korean native model; this test explicitly exercises the retained baseline.
        graph.speechFactoryOverride = { LocalSpeechInput(graph.speechModel) }
        openMap(); launch(waitForListening = false)
        await(45000) { assertEquals(entry?.coordinator?.sessionId, graph.invocationDebug.last?.sessionId)
            assertEquals(InvocationState.NO_SPEECH.name, graph.invocationDebug.last?.result) }
        val trace = checkNotNull(graph.invocationDebug.last)
        assertEquals("", trace.execution); assertNotNull(trace.times["T2"])
        assertTrue(trace.stt!!.coldModel)
        File(directory, "invocation-real-microphone-silence-trace.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(trace))
    }
    @Test fun actualAudioFocusIsRequestedAndReleasedWithoutTransportResume() {
        media()
        val manager = context.getSystemService(AudioManager::class.java)
        val changes = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val music = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ changes += it }, Handler(Looper.getMainLooper())).build()
        val home = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            home.onActivity { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(music)) }
            openMap(); launch()
            await { assertTrue(changes.contains(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) }
            feed { it.onFinal("노래 멈춰") }; result(InvocationState.SUCCESS)
            await { assertEquals(1, pauses.get()); assertTrue(changes.contains(AudioManager.AUDIOFOCUS_GAIN)) }
            assertEquals(0, plays.get())
            File(directory, "invocation-audio-focus.txt").writeText("Real AudioManager callback changes: $changes\nNo actual Bluetooth or acoustic ducking tested\nUser pause callbacks: ${pauses.get()}\nAgent resume callbacks: ${plays.get()}\n")
        } finally { manager.abandonAudioFocusRequest(music); home.close() }
    }
    @Test fun realOfflineDecoderWorksColdThenWarmThroughAutoEntry() {
        val originalAirplane = device.executeShellCommand("settings get global airplane_mode_on").trim()
        device.executeShellCommand("cmd connectivity airplane-mode enable")
        val released = CountDownLatch(1); graph.speechModel.releaseForTest { released.countDown() }
        assertTrue(released.await(15, TimeUnit.SECONDS))
        val pcm = wavePcm()
        val traces = mutableListOf<InvocationTrace>()
        graph.speechFactoryOverride = { LocalSpeechInput(graph.speechModel) { BytesPcm(pcm) } }
        try {
            repeat(2) {
                openMap(); launch(waitForListening = false)
                await(45000) { assertEquals(entry?.coordinator?.sessionId, graph.invocationDebug.last?.sessionId)
                    assertEquals(InvocationState.SUCCESS.name, graph.invocationDebug.last?.result) }
                val trace = checkNotNull(graph.invocationDebug.last); traces += trace
                assertTrue(trace.transcript.contains("알람"))
                await { assertEquals("com.google.android.deskclock", device.currentPackageName) }
                assertEquals("1", device.executeShellCommand("settings get global airplane_mode_on").trim())
                device.executeShellCommand("pm clear com.google.android.deskclock") // Owned AVD synthetic alarm only.
                entry = null
            }
            assertTrue(traces[0].stt!!.coldModel); assertFalse(traces[1].stt!!.coldModel)
            assertTrue(traces[0].times.getValue("T2") < traces[0].times.getValue("T5"))
            File(directory, "invocation-offline-cold-warm.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(traces))
        } finally { if (originalAirplane != "1") device.executeShellCommand("cmd connectivity airplane-mode disable") }
    }
    private class BytesPcm(private val bytes: ByteArray) : PcmSource {
        private var offset = 0; @Volatile private var stopped = false
        override fun start() = Unit
        override fun read(buffer: ByteArray): Int {
            if (stopped || offset == bytes.size) return -1
            val count = minOf(buffer.size, bytes.size - offset); bytes.copyInto(buffer, 0, offset, offset + count); offset += count; return count
        }
        override fun stop() { stopped = true }; override fun close() = stop()
    }
    private fun wavePcm(): ByteArray {
        val wave = instrumentation.context.assets.open("synthetic-alarm-ko.wav").use { it.readBytes() }
        var offset = 12
        while (offset + 8 <= wave.size) {
            val length = ByteBuffer.wrap(wave, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int; val data = offset + 8
            check(length >= 0 && data + length <= wave.size)
            if (String(wave, offset, 4, Charsets.US_ASCII) == "data") return wave.copyOfRange(data, data + length)
            offset = data + length + length % 2
        }
        error("Missing PCM")
    }
}
