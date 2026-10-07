package dev.localphone.agent.runtime

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import dev.localphone.agent.AgentApplication
import dev.localphone.core.*
import java.util.UUID
import kotlinx.coroutines.*

/** Lifecycle owner delegates microphone, planning, policy, and effects here. All callbacks enter on main. */
class VoiceSessionCoordinator(
    private val graph: AgentApplication,
    private val engine: AgentEngine,
    private val scope: CoroutineScope,
    private val focus: CaptureFocus,
    private val feedback: VoiceFeedback,
    private val invokedAt: Long,
    private val observer: Observer,
) {
    interface Observer {
        fun onState(state: InvocationState, message: String)
        fun onLevel(level: Float)
        fun onFinished(state: InvocationState)
    }
    val sessionId = UUID.randomUUID().toString()
    private val machine = VoiceSessionStateMachine()
    val state get() = machine.state
    val active get() = ownsLease && !state.terminal
    private var ownsLease = false
    private var speech: SpeechInput? = null
    private var speechToken = 0
    private var work: Job? = null
    private var deadline: Job? = null
    private var warming: Deferred<ModelPreparation>? = null
    private var clarification: ClarificationRequest? = null
    private val times = linkedMapOf("T0" to 0L)
    private val transitions = mutableListOf<String>()
    private var transcript = ""
    private var clarificationText = ""
    private var metrics: SpeechMetrics? = null
    private var planText = ""
    private var policyText = ""
    private var executionText = ""
    private var interpretation = "DETERMINISTIC_GOAL_FAST_PATH"
    private var disposition = ResultDisposition.BACKGROUND_ACTION
    private var focusStatus = "NOT_REQUESTED"
    private var duplicates = 0
    private val useModel = graph.settings.get("use_functiongemma") == "yes"
    private fun mark(name: String) { times.putIfAbsent(name, (SystemClock.elapsedRealtime() - invokedAt).coerceAtLeast(0)) }
    private fun markAt(name: String, elapsed: Long) { times.putIfAbsent(name, (elapsed - invokedAt).coerceAtLeast(0)) }

    fun start(): Boolean {
        if (ownsLease) { duplicate(); return true }
        if (!graph.invocationArbiter.acquire(sessionId)) return false
        ownsLease = true; mark("T1"); move(InvocationState.STARTING, "준비 중…")
        if (!engine.profile.canAct()) { finish(InvocationState.AUTH_REQUIRED, "잠금 해제 후 다시 호출해 주세요."); return true }
        if (graph.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finish(InvocationState.PERMISSION_REQUIRED, "마이크 권한을 허용해 주세요."); return true
        }
        listen()
        // Capture is started first; local Agent initialization runs while the user speaks.
        if (useModel && active) warming = scope.async { graph.planner.prepare() }
        return true
    }
    fun duplicate() { if (active) duplicates++ }
    private fun move(next: InvocationState, message: String) {
        if (machine.move(next)) { transitions += next.name; observer.onState(next, message) }
    }
    private fun listen() {
        if (!focus.acquire { if (active) finish(InvocationState.CANCELLED, "다른 오디오 작업으로 취소했습니다.") }) {
            focusStatus = "DENIED"; finish(InvocationState.EXECUTION_FAILED, "음성 입력을 위한 오디오 사용 권한을 얻지 못했습니다."); return
        }
        focusStatus = "TRANSIENT_MAY_DUCK"
        val token = ++speechToken
        val source = graph.createSpeechInput(); speech = source
        deadline?.cancel()
        // Also bounds engines that never send a ready callback.
        deadline = scope.launch { delay(10000); if (active && token == speechToken && state == InvocationState.STARTING)
            finish(InvocationState.STT_FAILED, "마이크를 시작하지 못했습니다.") }
        source.start(object : SpeechInput.Listener {
            private fun current() = active && token == speechToken && state in listOf(InvocationState.STARTING, InvocationState.LISTENING, InvocationState.TRANSCRIBING)
            override fun onListening() {
                if (!current()) return
                mark(if (clarification == null) "T2" else "C_T2")
                move(InvocationState.LISTENING, clarification?.prompt ?: "듣는 중…")
                feedback.ready()
                deadline?.cancel()
                deadline = scope.launch {
                    delay(graph.speechTiming().maxUtteranceMs.toLong() + 12000)
                    if (current()) finish(InvocationState.STT_FAILED, "음성 처리가 오래 걸려 취소했습니다.")
                }
            }
            override fun onListeningAt(elapsedRealtimeMs: Long) {
                if (!current()) return
                markAt(if (clarification == null) "T2" else "C_T2", elapsedRealtimeMs)
                onListening()
            }
            override fun onLevel(level: Float) { if (current()) observer.onLevel(level) }
            override fun onPartial(text: String) { /* Partial text never reaches AgentEngine. */ }
            override fun onSpeechStarted() { if (current()) mark(if (clarification == null) "T3" else "C_T3") }
            override fun onSpeechStartedAt(elapsedRealtimeMs: Long) { if (current()) markAt(if (clarification == null) "T3" else "C_T3", elapsedRealtimeMs) }
            override fun onSpeechEndedAt(elapsedRealtimeMs: Long) {
                if (!current()) return
                markAt(if (clarification == null) "T4" else "C_T4", elapsedRealtimeMs)
                onSpeechEnded()
            }
            override fun onSpeechEnded() {
                if (!current()) return
                mark(if (clarification == null) "T4" else "C_T4")
                focus.release() // Unduck before any user media operation; never send transport play.
                move(InvocationState.TRANSCRIBING, "처리 중…")
            }
            override fun onMetrics(metrics: SpeechMetrics) { if (current()) this@VoiceSessionCoordinator.metrics = metrics }
            override fun onFinal(text: String) {
                if (!current()) return
                if (state == InvocationState.STARTING) { finish(InvocationState.STT_FAILED, "마이크 준비를 확인하지 못했습니다."); return }
                if (state != InvocationState.TRANSCRIBING) onSpeechEnded()
                mark(if (clarification == null) "T5" else "C_T5")
                speechToken++; source.cancel(); speech = null; deadline?.cancel(); focus.release()
                val clean = text.trim().trimEnd('.', '。', '!')
                if (clean.isBlank()) { finish(InvocationState.NO_SPEECH, "음성을 듣지 못했습니다."); return }
                if (clarification == null) transcript = clean else clarificationText = clean
                process(clean)
            }
            override fun onError(message: String) { if (current()) finish(InvocationState.STT_FAILED, message) }
            override fun onFailure(failure: SpeechFailure) {
                if (!current()) return
                val state = when (failure.code) {
                    SpeechError.NO_SPEECH -> InvocationState.NO_SPEECH
                    SpeechError.PERMISSION_REQUIRED -> InvocationState.PERMISSION_REQUIRED
                    SpeechError.STT_FAILED -> InvocationState.STT_FAILED
                }
                finish(state, failure.message)
            }
        })
    }
    private fun process(text: String) {
        work = scope.launch {
            try {
                val request = if (clarification != null) {
                    move(InvocationState.PLANNING, "장소 확인 중…")
                    move(InvocationState.POLICY_CHECK, "처리 중…")
                    engine.clarify(checkNotNull(clarification), text)
                } else {
                    engine.prepare(text, useModel) { stage ->
                        if (stage == EngineStage.PLANNING) move(InvocationState.PLANNING, "처리 중…")
                        else { mark("T6"); move(InvocationState.POLICY_CHECK, "처리 중…") }
                    }
                }
                if (!active) return@launch
                planText = request.plan.toString(); policyText = request.decision.toString(); interpretation = request.interpretation
                if (request.clarification != null && machine.clarifyOnce()) {
                    clarification = request.clarification
                    transitions += "AMBIGUOUS_CLARIFICATION_ONCE"; transitions += InvocationState.STARTING.name
                    observer.onState(InvocationState.STARTING, request.clarification.prompt)
                    listen(); return@launch
                }
                val ready = request.decision as? PolicyDecision.Ready
                if (request.failure != null || ready == null) {
                    val failure = request.failure ?: AgentFailure(InvocationState.POLICY_BLOCKED, "작업을 확인하지 못했습니다.")
                    finish(failure.state, failure.message); return@launch
                }
                engine.preflight(ready)?.let { finish(it.state, it.message); return@launch }
                ensureActive()
                if (!active) return@launch
                if (state != InvocationState.POLICY_CHECK) { finish(InvocationState.POLICY_BLOCKED, "음성 세션 상태를 확인하지 못했습니다."); return@launch }
                mark("T7"); move(InvocationState.EXECUTING, "실행 중…")
                val outcome = engine.execute(ready)
                mark("T8"); disposition = outcome.disposition
                executionText = (outcome.execution?.toString() ?: "NOT_DISPATCHED") + "; evidence=" + outcome.evidence.joinToString(",")
                outcome.failure?.let { finish(it.state, it.message); return@launch }
                finish(InvocationState.SUCCESS, outcome.execution?.message.orEmpty())
            } catch (cancelled: CancellationException) { if (active) finish(InvocationState.CANCELLED, "화면 작업을 취소했습니다.") }
            catch (_: Exception) { if (active) finish(InvocationState.EXECUTION_FAILED, "작업을 처리하지 못했습니다.") }
        }
    }
    fun cancel() { if (active) finish(InvocationState.CANCELLED, "취소했습니다.") }
    private fun finish(result: InvocationState, error: String) {
        if (!active) return
        move(result, error)
        speechToken++; speech?.cancel(); speech = null
        deadline?.cancel(); focus.release()
        if (result == InvocationState.SUCCESS) feedback.success()
        else if (result != InvocationState.CANCELLED) feedback.failure()
        graph.invocationDebug.write(InvocationTrace(sessionId = sessionId, profile = engine.profile.user,
            interpretation = interpretation,
            times = times.toMap(), transitions = transitions.toList(), transcript = transcript,
            clarification = clarificationText, stt = metrics, agentLoad = if (useModel) graph.planner.lastLoad else null,
            modelInferenceMs = if (useModel) graph.planner.lastInferenceMs else null, toolPlan = planText,
            policy = policyText, execution = executionText, disposition = disposition.name, result = result.name,
            error = if (result == InvocationState.SUCCESS) "" else error, audioFocus = focusStatus,
            duplicateInvocations = duplicates, totalMs = SystemClock.elapsedRealtime() - invokedAt))
        graph.invocationArbiter.release(sessionId); ownsLease = false
        warming?.cancel(); work?.cancel()
        if (useModel) scope.launch(NonCancellable) { graph.planner.close() } // No unmeasured permanent Agent cache.
        observer.onFinished(result)
    }
}
