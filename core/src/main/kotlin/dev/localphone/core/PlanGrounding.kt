package dev.localphone.core

/** A model may extract or normalize an alias, but cannot invent a destination. */
object PlanGrounding {
    fun validate(plan: ToolPlan, utterance: String): ToolPlan {
        if (plan.unsupportedReason != null) return plan
        val input = PlaceText.normalize(utterance)
        for (action in plan.actions) {
            when (action) {
                is Action.AppTask -> {
                    if (action.goal.isBlank() || !input.contains(PlaceText.normalize(action.goal)) ||
                        (action.appName.isNotBlank() && !input.contains(PlaceText.normalize(action.appName)))) return rejected("앱 작업 목표")
                }
                is Action.Navigate -> {
                    val destination = PlaceText.normalize(action.destination)
                    val slot = PlaceSlots.slotFor(action.destination)
                    val grounded = if (slot != null) {
                        input.contains(PlaceText.normalize(slot)) || PlaceSlots.aliases.getValue(slot).any { input.contains(PlaceText.normalize(it)) }
                    } else destination.isNotBlank() && input.contains(destination)
                    if (!grounded) return ToolPlan(emptyList(), "모델의 목적지가 입력한 장소와 일치하지 않습니다. 실행하지 않았습니다.")
                }
                Action.MediaResume, Action.MediaPause, Action.MediaNext -> {
                    val grounded = MediaCommands.parse(utterance.trim().trimEnd('.', '。', '!')) == action ||
                        (action == Action.MediaResume && Regex("(?:노래|음악)\\s*(?:를\\s*)?(?:재생(?:해\\s*줘|해)?|틀어(?:\\s*줘)?|켜(?:\\s*줘)?)").containsMatchIn(utterance) &&
                            !Regex("멈춰|중지|일시\\s*정지|다음|꺼|저장|추가").containsMatchIn(utterance))
                    if (!grounded) return rejected("음악 동작")
                }
                is Action.OpenApp -> {
                    val requested = PhoneCommands.parse(utterance)?.actions?.filterIsInstance<Action.OpenApp>()?.singleOrNull()
                    val normalized = action.appName.lowercase().filterNot(Char::isWhitespace)
                    if (requested?.appName?.lowercase()?.filterNot(Char::isWhitespace) != normalized) return rejected("앱 이름")
                }
                is Action.SetAlarm -> {
                    val parsed = PhoneCommands.parse(utterance)
                    if (parsed?.actions?.contains(action) != true) return rejected("알람 시간")
                }
                is Action.SetTimer -> {
                    val parsed = PhoneCommands.parse(utterance)
                    if (parsed?.actions?.contains(action) != true) return rejected("타이머 시간")
                }
                is Action.OpenSettings -> {
                    if (PhoneCommands.parse(utterance)?.actions?.contains(action) != true) return rejected("설정 화면")
                }
            }
        }
        return plan
    }
    private fun rejected(field: String) = ToolPlan(emptyList(), "모델의 $field 요청이 입력과 일치하지 않거나 추가 확인이 필요합니다. 실행하지 않았습니다.")
}
