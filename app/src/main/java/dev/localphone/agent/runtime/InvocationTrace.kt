package dev.localphone.agent.runtime

import android.os.SystemClock
import com.google.gson.Gson
import dev.localphone.agent.BuildConfig
import dev.localphone.agent.data.SecureSettings
import java.util.concurrent.Executors

data class InvocationTrace(val sessionId: String, val invokedAt: Long = System.currentTimeMillis(),
    val origin: String = "ANDROID_APP_LAUNCH", val profile: String, val interpretation: String,
    val times: Map<String, Long>, val transitions: List<String>, val transcript: String,
    val clarification: String, val stt: SpeechMetrics?, val agentLoad: ModelPreparation?,
    val modelInferenceMs: Long?, val toolPlan: String, val policy: String, val execution: String,
    val disposition: String, val result: String, val error: String, val audioFocus: String,
    val duplicateInvocations: Int, val totalMs: Long, val speech: SpeechDiagnostic? = null,
    val selectedModel: String = "", val modelResponse: ModelResponse? = null,
    val appVersion: String = BuildConfig.VERSION_NAME, val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val modelEnabled: Boolean = true, val uiModel: String = "")

/** One bounded encrypted trace, private to this installation. Never logcat or shared storage. */
class InvocationDebugStore(private val settings: SecureSettings) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "local-invocation-log") }
    @Volatile var last: InvocationTrace? = null
        private set
    fun write(trace: InvocationTrace) {
        last = trace
        // Keep routing/model metadata across process restarts even when detailed speech
        // diagnostics are off. Command text and model/screen contents remain opt-in.
        val persisted = if (BuildConfig.DEBUG || settings.get("stt_diagnostics") == "yes") trace else trace.copy(
            transcript = "", clarification = "", speech = null, stt = null, toolPlan = "", policy = "", execution = "",
            modelResponse = null, error = "", interpretation = trace.interpretation.substringBefore(';'))
        worker.execute { runCatching { settings.put("last_voice_trace", Gson().toJson(persisted)) } }
    }
    fun read(): InvocationTrace? = last ?: runCatching {
        Gson().fromJson(settings.get("last_voice_trace"), InvocationTrace::class.java)
    }.getOrNull()
}

class InvocationArbiter {
    private var active: String? = null
    val isActive get() = synchronized(this) { active != null }
    @Synchronized fun acquire(id: String): Boolean { if (active != null) return false; active = id; return true }
    @Synchronized fun release(id: String) { if (active == id) active = null }
}
