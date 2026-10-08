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
    val modelEnabled: Boolean = true, val uiModel: String = "", val audit: InvocationAudit? = null)

/** Bounded encrypted history, private to this installation; export is a user action. */
class InvocationDebugStore(private val settings: SecureSettings) {
    companion object { const val MAX_RECORDS = 30; const val HISTORY_KEY = "voice_trace_history_v1" }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "local-invocation-log") }
    private val gson = Gson()
    private var history: List<InvocationTrace>? = null
    private var epoch = 0L
    @Volatile var last: InvocationTrace? = null
        private set
    fun detailsEnabled() = BuildConfig.DEBUG || settings.get("stt_diagnostics") == "yes"
    @Synchronized fun generation() = epoch
    @Synchronized fun write(trace: InvocationTrace, generation: Long? = null) {
        if (generation == null || generation == epoch) save(trace, terminal = true)
    }
    @Synchronized fun checkpoint(trace: InvocationTrace, generation: Long? = null) {
        if (generation == null || generation == epoch) save(trace, terminal = false)
    }
    private fun save(trace: InvocationTrace, terminal: Boolean) {
        val previous = readHistory()
        if (terminal || last?.sessionId == trace.sessionId) last = trace
        // Keep routing/model metadata across process restarts even when detailed speech
        // diagnostics are off. Command text and model/screen contents remain opt-in.
        val persisted = if (detailsEnabled()) trace else trace.copy(
            transcript = "", clarification = "", speech = null, stt = null, toolPlan = "", policy = "", execution = "",
            modelResponse = null, error = "", audit = null, interpretation = trace.interpretation.substringBefore(';'))
        val records = (previous.filterNot { it.sessionId == trace.sessionId } + persisted)
            .sortedByDescending { it.invokedAt }.take(MAX_RECORDS).toMutableList()
        // Retain recent calls without letting encrypted preferences grow indefinitely.
        while (records.size > 1 && gson.toJson(records).toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024) records.removeAt(records.lastIndex)
        history = records.toList()
        val encoded = gson.toJson(history)
        worker.execute { runCatching {
            settings.putDurable(HISTORY_KEY, encoded)
            if (terminal) settings.putDurable("last_voice_trace", gson.toJson(persisted))
        } }
    }
    @Synchronized fun readHistory(): List<InvocationTrace> {
        history?.let { return it }
        val restored = runCatching { gson.fromJson(settings.get(HISTORY_KEY), Array<InvocationTrace>::class.java)?.toList() }.getOrNull()
        history = restored?.take(MAX_RECORDS) ?: listOfNotNull(read())
        return history!!
    }
    @Synchronized fun clear() {
        epoch++ // Old lifecycle/capture callbacks cannot resurrect a deleted invocation.
        last = null; history = emptyList()
        worker.execute { settings.putDurable(HISTORY_KEY, ""); settings.putDurable("last_voice_trace", "") }
    }
    /** A barrier for explicit file export/tests; never used on the microphone callback thread. */
    fun awaitPersistence() { worker.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS) }
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
