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
    val duplicateInvocations: Int, val totalMs: Long, val speech: SpeechDiagnostic? = null)

/** One bounded encrypted trace, private to this installation. Never logcat or shared storage. */
class InvocationDebugStore(private val settings: SecureSettings) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "local-invocation-log") }
    @Volatile var last: InvocationTrace? = null
        private set
    fun write(trace: InvocationTrace) {
        last = trace
        if (!BuildConfig.DEBUG && settings.get("stt_diagnostics") != "yes") return
        worker.execute { runCatching { settings.put("last_voice_trace", Gson().toJson(trace)) } }
    }
    fun read(): InvocationTrace? = last ?: runCatching {
        Gson().fromJson(settings.get("last_voice_trace"), InvocationTrace::class.java)
    }.getOrNull()
}

class InvocationArbiter {
    private var active: String? = null
    @Synchronized fun acquire(id: String): Boolean { if (active != null) return false; active = id; return true }
    @Synchronized fun release(id: String) { if (active == id) active = null }
}
