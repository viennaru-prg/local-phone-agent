package dev.localphone.core

enum class InvocationState {
    IDLE, STARTING, LISTENING, TRANSCRIBING, PLANNING, POLICY_CHECK, EXECUTING,
    SUCCESS, NO_SPEECH, AMBIGUOUS, LOW_CONFIDENCE, POLICY_BLOCKED, EXECUTION_FAILED,
    CANCELLED, PERMISSION_REQUIRED, STT_FAILED, NETWORK_REQUIRED, MODEL_UNAVAILABLE, AUTH_REQUIRED;
    val terminal get() = this !in setOf(IDLE, STARTING, LISTENING, TRANSCRIBING, PLANNING, POLICY_CHECK, EXECUTING)
}

/** Only forward transitions may authorize effects; a finished or cancelled session cannot restart. */
class VoiceSessionStateMachine {
    var state = InvocationState.IDLE
        private set
    var clarificationUsed = false
        private set
    fun move(next: InvocationState): Boolean {
        if (state.terminal) return false
        val allowed = (next.terminal && (next != InvocationState.SUCCESS || state == InvocationState.EXECUTING)) || when (state) {
            InvocationState.IDLE -> next == InvocationState.STARTING
            InvocationState.STARTING -> next == InvocationState.LISTENING
            InvocationState.LISTENING -> next == InvocationState.TRANSCRIBING
            InvocationState.TRANSCRIBING -> next == InvocationState.PLANNING
            InvocationState.PLANNING -> next == InvocationState.POLICY_CHECK
            InvocationState.POLICY_CHECK -> next == InvocationState.EXECUTING
            else -> false
        }
        if (allowed) state = next
        return allowed
    }
    fun clarifyOnce(): Boolean {
        if (state != InvocationState.POLICY_CHECK || clarificationUsed) return false
        clarificationUsed = true; state = InvocationState.STARTING
        return true
    }
}

data class ClarificationRequest(val originalPlan: ToolPlan, val candidates: List<PlaceCandidate>, val prompt: String) {
    fun select(utterance: String): PlaceCandidate? {
        val name = PlaceText.normalize(utterance)
        if (name.isBlank()) return null
        return candidates.filter { PlaceText.normalize(it.name) == name }.singleOrNull()
    }
}
