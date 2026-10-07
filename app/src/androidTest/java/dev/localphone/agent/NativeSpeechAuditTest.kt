package dev.localphone.agent

import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.gson.GsonBuilder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly selected hardware audit: queries support only; no microphone or settings writes. */
@RunWith(AndroidJUnit4::class)
class NativeSpeechAuditTest {
    @Test fun readOnlyNativeKoreanSupport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        assumeTrue("This audit is explicitly for the selected S25 owner profile",
            device.executeShellCommand("getprop ro.product.model").trim() == "SM-S938N" &&
                device.executeShellCommand("am get-current-user").trim() == "0")
        val report = linkedMapOf<String, Any>("sdk" to Build.VERSION.SDK_INT,
            "microphone_started" to false, "settings_changed" to false, "locale" to "ko-KR")
        val done = CountDownLatch(1)
        var recognizer: SpeechRecognizer? = null
        instrumentation.runOnMainSync {
            val graph = context.applicationContext as AgentApplication
            report["currently_selected_input_class"] = graph.createSpeechInput().javaClass.simpleName
            val available = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            report["on_device_api_available"] = available
            if (!available) { report["support_status"] = "NO_ON_DEVICE_SERVICE"; done.countDown(); return@runOnMainSync }
            try {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                report["created_api"] = "createOnDeviceSpeechRecognizer"
                if (Build.VERSION.SDK_INT < 33) { report["support_status"] = "LANGUAGE_QUERY_REQUIRES_API_33"; done.countDown(); return@runOnMainSync }
                recognizer!!.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        report["support_status"] = "QUERY_COMPLETED"
                        report["installed_on_device_languages"] = support.installedOnDeviceLanguages
                        report["pending_on_device_languages"] = support.pendingOnDeviceLanguages
                        report["supported_on_device_languages"] = support.supportedOnDeviceLanguages
                        report["online_languages"] = support.onlineLanguages
                        report["korean_ready"] = support.installedOnDeviceLanguages.any { it.startsWith("ko", ignoreCase = true) }
                        done.countDown()
                    }
                    override fun onError(error: Int) { report["support_status"] = "QUERY_ERROR"; report["support_error"] = error; done.countDown() }
                })
            } catch (error: Exception) {
                report["support_status"] = "QUERY_EXCEPTION"; report["exception_type"] = error.javaClass.simpleName; done.countDown()
            }
        }
        val completed = done.await(25, TimeUnit.SECONDS)
        if (!completed) report["support_status"] = "QUERY_TIMEOUT"
        instrumentation.runOnMainSync { recognizer?.destroy() }
        val output = File(context.getExternalFilesDir(null), "stt-evaluation/audit.json")
        output.parentFile!!.mkdirs()
        output.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        assertTrue("The API support query timed out; no native STT pass is inferred", completed)
    }
}
