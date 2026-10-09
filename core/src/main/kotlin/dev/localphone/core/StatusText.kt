package dev.localphone.core

/**
 * The one-line progress shown on the overlay while a command runs: what is being done in plain words
 * ("‘길찾기’ 누르는 중") instead of the step log ("3. click [5] "길찾기" → 화면 바뀜"). Null for
 * bookkeeping steps, which leave the current line as it is.
 */
object StatusText {
    private val step = Regex("""^(\w+)(?: \[\d+])?(?: "((?:[^"\\]|\\.)*)")?(?: "((?:[^"\\]|\\.)*)")?""")

    fun forStep(action: String): String? {
        val m = step.find(action) ?: return null
        val (op, first, second) = m.destructured
        val label = short(first)
        return when (op) {
            "click", "double_tap" -> if (label.isEmpty()) "누르는 중…" else "‘$label’ 누르는 중…"
            "long_click" -> if (label.isEmpty()) "길게 누르는 중…" else "‘$label’ 길게 누르는 중…"
            "click_end" -> if (label.isEmpty()) "버튼 누르는 중…" else "‘$label’ 옆 버튼 누르는 중…"
            "type" -> "‘${short(second)}’ 입력하는 중…"
            "scroll" -> "목록 넘기는 중…"
            "open_app" -> "${short(first)} 여는 중…"
            "open_map" -> "지도 여는 중…"
            "observe" -> "화면 상태 확인 중…"
            "back" -> "뒤로 가는 중…"
            "wait" -> "화면 기다리는 중…"
            "media" -> "음악 조작 중…"
            "inspect" -> "화면 살펴보는 중…"
            "verify" -> "결과 확인 중…"
            "choose_place" -> "목적지 고르는 중…"
            else -> null
        }
    }

    private fun short(label: String): String = label.replace("\\\"", "\"").trim().let { if (it.length > 18) it.take(17).trimEnd() + "…" else it }
}
