package dev.localphone.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.gson.GsonBuilder
import dev.localphone.agent.runtime.*
import dev.localphone.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Selected S25 only. Synthetic replay smoke, never counted as the user's natural-speech benchmark. */
@RunWith(AndroidJUnit4::class)
class NativeSpeechReplayTest {
    @Test fun providedSyntheticPcmCompletesNativeKoreanRecognition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        assumeTrue(device.executeShellCommand("getprop ro.product.model").trim() == "SM-S938N" &&
            device.executeShellCommand("am get-current-user").trim() == "0")
        assertEquals("Microphone permission must be manually granted; this audit never grants it", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val wave = instrumentation.context.assets.open("synthetic-alarm-ko.wav").use { it.readBytes() }
        var position = 12; var pcm: ByteArray? = null
        while (position + 8 <= wave.size) {
            val name = String(wave, position, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(wave, position + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(size >= 0 && position + 8 + size <= wave.size)
            if (name == "data") { pcm = wave.copyOfRange(position + 8, position + 8 + size); break }
            position += size + 8 + size % 2
        }
        val audio = checkNotNull(pcm)
        val before = evaluationNetwork(context)
        val completed = CountDownLatch(1)
        var speech: SpeechInput? = null
        var result: SpeechRecognitionResult? = null
        var error: String? = null
        val start = SystemClock.elapsedRealtime()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            instrumentation.runOnMainSync {
                speech = OnDeviceSpeechInput(context, listOf("알람", "오전"), replaySource = { evaluationReplaySource(audio) })
                speech!!.start(object : SpeechInput.Listener {
                    override fun onListening() = Unit
                    override fun onLevel(level: Float) = Unit
                    override fun onPartial(text: String) = Unit
                    override fun onFinal(text: String) = Unit
                    override fun onRecognition(value: SpeechRecognitionResult) { result = value; completed.countDown() }
                    override fun onError(message: String) { error = message; completed.countDown() }
                })
            }
            val signaled = completed.await(30, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { speech?.cancel() }
            val report = linkedMapOf<String, Any?>("scope" to "SYNTHETIC_PCM_SMOKE_NOT_USER_NATURAL_SPEECH",
                "version" to BuildConfig.VERSION_NAME, "engine" to result?.engine,
                "api_on_device" to result?.onDevice, "replay_requested" to true,
                "offline_before_and_after" to (before.offline && evaluationNetwork(context).offline),
                "signaled" to signaled, "error" to error, "elapsed_ms" to (SystemClock.elapsedRealtime() - start),
                "hypotheses" to result?.hypotheses, "latency" to result?.totalLatencyMs,
                "speech_source" to result?.source, "no_command_execution" to true)
            val file = File(context.getExternalFilesDir(null), "stt-evaluation/native-replay.json")
            file.parentFile!!.mkdirs(); file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
            assertTrue("No native recognition callback", signaled)
            assertNull("Native replay failed: $error", error)
            assertTrue("The provided fixture must be recognized; no replay support is inferred from an ignored extra",
                result?.hypotheses?.any { it.text.contains("알람") && SpokenTime.normalize(it.text).contains("7시") } == true)
        }
    }
}
