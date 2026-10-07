package dev.localphone.core

data class SpeechBenchmarkCase(val id: String, val text: String, val category: String)
data class SpeechBenchmarkMeasure(val exact: Boolean, val semantic: Boolean?, val intent: Boolean?,
    val entity: Boolean?, val toolPlan: Boolean?, val referencePlan: String, val observedPlan: String,
    val execution: String = "NOT_RUN_STT_EVALUATION_ONLY")
object SpeechBenchmark {
    private fun entity(value: String, kind: SpeechEntityKind, context: SpeechContext): String {
        if (kind == SpeechEntityKind.PLACE) PlaceSlots.slotFor(value)?.let { return it }
        return context.entities.filter { it.kind == kind && (it.aliases + it.name).any { name ->
            PlaceText.variants(value).any { it in PlaceText.variants(name) }
        } }.map { it.id }.distinct().singleOrNull() ?: PlaceText.variants(value).minBy { it.length }
    }
    private fun action(action: Action, context: SpeechContext): Pair<String, String> = when (action) {
        is Action.Navigate -> "navigate" to entity(action.destination, SpeechEntityKind.PLACE, context)
        is Action.OpenApp -> "open_app" to entity(action.appName, SpeechEntityKind.APP, context)
        is Action.SetAlarm -> "set_alarm" to "${action.hour}:${action.minute}"
        is Action.SetTimer -> "set_timer" to action.seconds.toString()
        is Action.OpenSettings -> "open_settings" to action.page.key
        is Action.AppTask -> "app_task" to "${action.appName}:${action.goal}"
        Action.MediaResume -> "media_resume" to ""
        Action.MediaPause -> "media_pause" to ""
        Action.MediaNext -> "media_next" to ""
    }
    suspend fun measure(reference: String, observed: String, context: SpeechContext, blocked: Boolean): SpeechBenchmarkMeasure {
        val referenceResolved = ContextualTranscriptResolver().resolve(SpeechRecognitionResult(
            listOf(SpeechHypothesis(reference, null, 0)), engine = "REFERENCE", onDevice = true), context).selectedText
        val expected = BasicCommandPlanner().plan(referenceResolved).actions.map { action(it, context) }
        val actual = if (blocked) emptyList() else BasicCommandPlanner().plan(observed).actions.map { action(it, context) }
        val exact = reference.trim().trimEnd('.', '!') == observed.trim().trimEnd('.', '!')
        if (CommandSafety.blockedReason(reference) != null) {
            val preserved = CommandSafety.blockedReason(observed) != null
            return SpeechBenchmarkMeasure(exact, preserved, preserved, null, actual.isEmpty(),
                "NO_EXECUTION_NEGATED_OR_CONDITIONAL", actual.toString())
        }
        if (expected.isEmpty()) return SpeechBenchmarkMeasure(exact, if (PlaceText.normalize(reference) == PlaceText.normalize(observed)) true else null,
            null, null, null, "UNSUPPORTED_BY_CURRENT_AGENT", actual.toString())
        val intent = expected.map { it.first } == actual.map { it.first }
        val entities = expected.map { it.second } == actual.map { it.second }
        return SpeechBenchmarkMeasure(exact, intent && entities, intent, entities, expected == actual,
            expected.toString(), actual.toString())
    }
}
