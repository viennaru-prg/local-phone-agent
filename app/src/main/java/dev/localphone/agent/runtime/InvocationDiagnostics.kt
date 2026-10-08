package dev.localphone.agent.runtime

import android.app.Activity
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import dev.localphone.agent.AgentApplication
import dev.localphone.agent.BuildConfig
import dev.localphone.core.*
import java.util.UUID

data class InvocationEvent(val sequence: Int, val elapsedMs: Long, val name: String,
    val thread: String, val payload: JsonElement?)
data class InvocationAudit(val schemaVersion: Int = 1, val enabled: Boolean, val comparisonGroup: String,
    val snapshot: Map<String, String>, val events: List<InvocationEvent>, val droppedEvents: Int)

/** One recorder per invocation. Nothing is sent to a server or to logcat. */
class InvocationDiagnostics(private val graph: AgentApplication, private val host: Activity,
    val origin: String, val startedAt: Long = SystemClock.elapsedRealtime(), val id: String = UUID.randomUUID().toString()) {
    private val gson = Gson()
    private val enabled = graph.invocationDebug.detailsEnabled()
    private val generation = graph.invocationDebug.generation()
    private val wallTime = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAt)
    private val comparisonGroup = graph.settings.get("diagnostic_comparison_group")
    private val events = mutableListOf<InvocationEvent>()
    private var dropped = 0
    private var sequence = 0
    private var eventBytes = 0
    private var completed: InvocationTrace? = null
    var speech: SpeechDiagnostic? = null
    val closed get() = completed != null
    private val snapshot = buildMap {
        put("pid", Process.myPid().toString()); put("sdk", Build.VERSION.SDK_INT.toString())
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}"); put("host", host.javaClass.simpleName)
        put("profile", ProfileScope(host).user); put("appVersion", BuildConfig.VERSION_NAME)
        put("appVersionCode", BuildConfig.VERSION_CODE.toString()); put("selectedModel", graph.planner.selected.key)
        put("modelEnabled", graph.useAgentModel.toString()); put("invocationLeaseActive", graph.invocationArbiter.isActive.toString())
        put("intentAction", host.intent?.action.orEmpty()); put("intentFlags", host.intent?.flags.toString())
        put("intentComponent", host.intent?.component?.flattenToShortString().orEmpty())
        put("intentCategories", host.intent?.categories?.sorted()?.joinToString().orEmpty())
        put("callingPackage", host.callingPackage.orEmpty())
        put("referrer", runCatching { host.referrer?.toString() }.getOrNull().orEmpty())
        put("automaticSourceLimit", "Android app launch does not prove a physical back-tap")
        for (key in listOf("speech_engine", "speech_start_ms", "speech_silence_ms", "speech_max_ms", "stt_focus_mode",
            "stt_environment", "stt_bias_limit", "voice_onboarded", "agent_model", "stt_diagnostics")) put(key, graph.settings.get(key))
    }
    init { event("BEGIN", mapOf("modelLoadAtEntry" to graph.planner.lastLoad)); checkpoint() }
    @Synchronized fun event(name: String, payload: Any? = null) {
        if (!enabled) return
        if (events.size >= 192) { dropped++; return }
        val data = runCatching { gson.toJsonTree(payload) }.getOrNull()
        val bounded = if (data != null && gson.toJson(data).toByteArray(Charsets.UTF_8).size > 96 * 1024)
            gson.toJsonTree(mapOf("truncated" to true, "preview" to gson.toJson(data).take(12_000))) else data
        val bytes = gson.toJson(bounded).toByteArray(Charsets.UTF_8).size
        if (eventBytes + bytes > 384 * 1024 && name != "END") { dropped++; return }
        eventBytes += bytes
        events += InvocationEvent(++sequence, (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0), name,
            Thread.currentThread().name, bounded)
        completed?.let { graph.invocationDebug.checkpoint(decorate(it), generation) }
    }
    private fun decorate(trace: InvocationTrace) = trace.copy(sessionId = id, invokedAt = wallTime,
        audit = if (enabled) InvocationAudit(enabled = true, comparisonGroup = comparisonGroup, snapshot = snapshot,
            events = events.toList(), droppedEvents = dropped) else null)
    @Synchronized fun checkpoint() {
        if (!enabled) return
        graph.invocationDebug.checkpoint(decorate(completed ?: minimal("IN_PROGRESS", "")), generation)
    }
    private fun minimal(result: String, error: String) = InvocationTrace(sessionId = id, origin = origin,
        profile = ProfileScope(host).user, interpretation = "NOT_PLANNED", times = emptyMap(), transitions = emptyList(),
        transcript = speech?.resolution?.selectedText.orEmpty(), clarification = "", stt = null, agentLoad = null,
        modelInferenceMs = null, toolPlan = "", policy = "", execution = "", disposition = "", result = result,
        error = error, audioFocus = "", duplicateInvocations = 0, totalMs = SystemClock.elapsedRealtime() - startedAt,
        speech = speech, selectedModel = snapshot.getValue("selectedModel"), modelEnabled = snapshot.getValue("modelEnabled").toBoolean())
    @Synchronized fun finish(trace: InvocationTrace) {
        if (completed != null) return
        event("END", mapOf("result" to trace.result, "error" to trace.error))
        completed = trace
        graph.invocationDebug.write(decorate(trace), generation)
    }
    fun finish(result: String, error: String = "") = finish(minimal(result, error))
    fun hostState(event: String, state: Any? = null) {
        this.event("HOST_$event", mapOf("state" to state, "focus" to host.hasWindowFocus(), "finishing" to host.isFinishing,
            "changingConfigurations" to host.isChangingConfigurations)); checkpoint()
    }
}

/** Capture callbacks before the owner filters them, including ignored late/duplicate results. */
fun SpeechInput.startAudited(listener: SpeechInput.Listener, audit: InvocationDiagnostics, ownerState: () -> String) {
    fun event(name: String, value: Any? = null) = audit.event(name, mapOf("ownerState" to ownerState(), "value" to value))
    start(object : SpeechInput.Listener {
        override fun onListening() { event("MIC_READY"); listener.onListening(); audit.checkpoint() }
        override fun onListeningAt(elapsedRealtimeMs: Long) { event("MIC_READY", elapsedRealtimeMs); listener.onListeningAt(elapsedRealtimeMs); audit.checkpoint() }
        override fun onLevel(level: Float) = listener.onLevel(level)
        override fun onPartial(text: String) { event("ASR_PARTIAL", text); listener.onPartial(text) }
        override fun onFinal(text: String) { event("ASR_LEGACY_FINAL", text); listener.onFinal(text); audit.checkpoint() }
        override fun onRecognition(result: SpeechRecognitionResult) { event("ASR_FINAL", result); listener.onRecognition(result); audit.checkpoint() }
        override fun onSpeechStarted() { event("SPEECH_STARTED"); listener.onSpeechStarted() }
        override fun onSpeechStartedAt(elapsedRealtimeMs: Long) { event("SPEECH_STARTED", elapsedRealtimeMs); listener.onSpeechStartedAt(elapsedRealtimeMs) }
        override fun onSpeechEnded() { event("SPEECH_ENDED"); listener.onSpeechEnded(); audit.checkpoint() }
        override fun onSpeechEndedAt(elapsedRealtimeMs: Long) { event("SPEECH_ENDED", elapsedRealtimeMs); listener.onSpeechEndedAt(elapsedRealtimeMs); audit.checkpoint() }
        override fun onMetrics(metrics: SpeechMetrics) { event("STT_METRICS", metrics); listener.onMetrics(metrics) }
        override fun onError(message: String) { event("ASR_ERROR", message); listener.onError(message); audit.checkpoint() }
        override fun onFailure(failure: SpeechFailure) { event("ASR_FAILURE", failure); listener.onFailure(failure); audit.checkpoint() }
        override fun onDiagnostic(name: String, values: Map<String, Any?>) { event(name, values); listener.onDiagnostic(name, values) }
    })
}

data class InvocationDifference(val field: String, val manual: JsonElement, val automatic: JsonElement)
data class InvocationComparison(val status: String, val manualId: String?, val automaticId: String?,
    val sameLiteral: Boolean?, val differences: List<InvocationDifference>, val note: String)

object InvocationComparisons {
    private val gson = Gson()
    private fun value(trace: InvocationTrace, name: String): JsonElement {
        val payload = trace.audit?.events?.firstOrNull { it.name == name }?.payload ?: return JsonNull.INSTANCE
        // Owner state and token counters belong in the timeline, not in recognizer configuration comparisons.
        return if (name == "NATIVE_REQUEST" && payload.isJsonObject) payload.asJsonObject["value"] ?: JsonNull.INSTANCE else payload
    }
    private fun callbackValue(trace: InvocationTrace, name: String): JsonElement = value(trace, name).let {
        if (it.isJsonObject) it.asJsonObject["value"] ?: JsonNull.INSTANCE else JsonNull.INSTANCE
    }
    private fun raw(trace: InvocationTrace): String = trace.speech?.recognition?.hypotheses?.firstOrNull()?.text
        ?: runCatching { callbackValue(trace, "ASR_FINAL").asJsonObject["hypotheses"].asJsonArray.first().asJsonObject["text"].asString }.getOrNull()
        ?: runCatching { callbackValue(trace, "ASR_LEGACY_FINAL").asString }.getOrNull().orEmpty()
    private fun hypotheses(trace: InvocationTrace): JsonElement = trace.speech?.recognition?.hypotheses?.let { gson.toJsonTree(it) }
        ?: runCatching { callbackValue(trace, "ASR_FINAL").asJsonObject["hypotheses"] }.getOrNull() ?: JsonNull.INSTANCE
    fun compare(history: List<InvocationTrace>, group: String): InvocationComparison {
        val records = history.filter { group.isNotBlank() && it.audit?.comparisonGroup == group }
        val manuals = records.filter { it.origin == "MANUAL_MICROPHONE" }
        val autos = records.filter { it.origin == "ANDROID_APP_LAUNCH" }
        val pair = manuals.flatMap { manual -> autos.map { manual to it } }
            .filter { raw(it.first).isNotBlank() && PlaceText.normalize(raw(it.first)) == PlaceText.normalize(raw(it.second)) }
            .maxByOrNull { minOf(it.first.invokedAt, it.second.invokedAt) }
        val manual = pair?.first ?: manuals.firstOrNull(); val auto = pair?.second ?: autos.firstOrNull()
        if (manual == null || auto == null) return InvocationComparison("NEED_BOTH_ENTRIES", manual?.sessionId, auto?.sessionId, null,
            emptyList(), "같은 명령을 중앙 버튼과 자동 호출로 한 번씩 실행한 뒤 새로고침하세요.")
        val complete = raw(manual).isNotBlank() && raw(auto).isNotBlank()
        val same = complete && PlaceText.normalize(raw(manual)) == PlaceText.normalize(raw(auto))
        val differences = mutableListOf<InvocationDifference>()
        fun field(name: String, a: Any?, b: Any?) {
            val left = gson.toJsonTree(a) ?: JsonNull.INSTANCE; val right = gson.toJsonTree(b) ?: JsonNull.INSTANCE
            if (left != right) differences += InvocationDifference(name, left, right)
        }
        field("appVersion", manual.appVersion, auto.appVersion); field("profile", manual.profile, auto.profile)
        field("modelEnabled", manual.modelEnabled, auto.modelEnabled); field("selectedModel", manual.selectedModel, auto.selectedModel)
        for (key in listOf("pid", "speech_engine", "stt_focus_mode", "stt_environment", "stt_bias_limit", "agent_model"))
            field("entry.$key", manual.audit?.snapshot?.get(key), auto.audit?.snapshot?.get(key))
        field("rawHypotheses", hypotheses(manual), hypotheses(auto))
        field("speechContext", manual.speech?.context, auto.speech?.context)
        field("biasStrings", manual.speech?.context?.biasStrings(), auto.speech?.context?.biasStrings())
        field("resolution", manual.speech?.resolution, auto.speech?.resolution)
        for (name in listOf("NATIVE_REQUEST", "SPEECH_CONTEXT", "MODEL_REQUEST")) field(name, value(manual, name), value(auto, name))
        field("result", manual.result, auto.result); field("interpretation", manual.interpretation, auto.interpretation)
        field("times", manual.times, auto.times)
        return InvocationComparison(if (!complete) "INCOMPLETE_ASR" else if (same) "PAIRED" else "DIFFERENT_TRANSCRIPTS", manual.sessionId, auto.sessionId, if (complete) same else null,
            differences, "차이 목록은 원인 확정이 아닙니다. partial·callback 순서·판정 입력은 각 호출의 시간순 이벤트에서 확인하세요.")
    }
}
