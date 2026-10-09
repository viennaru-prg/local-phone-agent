package dev.localphone.core

/**
 * Everyday requests Android answers directly, in under a second: no screen, no model. The tool
 * (AndroidTools.quick) carries them out; the screen agent took 40-120 s for some of them
 * ("볼륨 좀 올려줘" wandered, "타이머 1분 맞춰줘" took 12 model steps).
 */
sealed interface QuickRequest {
    data object Time : QuickRequest
    data object Date : QuickRequest
    data object Battery : QuickRequest
    /** [steps] > 0 raises, < 0 lowers; [toMax]/[mute] for "최대로", "소리 꺼". */
    data class Volume(val steps: Int, val toMax: Boolean = false, val mute: Boolean = false) : QuickRequest
    data class Timer(val seconds: Int) : QuickRequest
    data class Alarm(val hour: Int, val minute: Int) : QuickRequest
}

object QuickCommands {
    fun parse(goal: String): QuickRequest? {
        if (GoalScope.multiple(goal)) return null
        val g = GoalText.normalize(goal)
        if (Regex("몇\\s*시|시간\\s*(?:알려|좀|뭐)|지금\\s*시간").containsMatchIn(goal) && !Regex("걸려|남았|타이머|알람|맞춰").containsMatchIn(goal)) return QuickRequest.Time
        if (Regex("며칠|몇\\s*일|무슨\\s*요일|날짜").containsMatchIn(goal) && !Regex("알람|일정").containsMatchIn(goal)) return QuickRequest.Date
        if (Regex("배터리").containsMatchIn(goal) && Regex("얼마|몇|남았|퍼센트|%|잔량").containsMatchIn(goal)) return QuickRequest.Battery
        volume(goal, g)?.let { return it }
        timer(goal)?.let { return it }
        alarm(goal)?.let { return it }
        return null
    }

    private fun volume(goal: String, g: String): QuickRequest? {
        if (!Regex("볼륨|소리|음량").containsMatchIn(goal)) return null
        if (Regex("최대|제일\\s*크게|끝까지").containsMatchIn(goal)) return QuickRequest.Volume(0, toMax = true)
        if (Regex("음소거|소리\\s*(?:꺼|끄|없애)|무음").containsMatchIn(goal)) return QuickRequest.Volume(0, mute = true)
        val amount = when { Regex("많이|팍|훨씬|크게\\s*크게").containsMatchIn(goal) -> 4; Regex("조금|살짝|약간|좀만").containsMatchIn(goal) -> 1; else -> 2 }
        return when {
            Regex("올려|키워|크게|높여|올리|업").containsMatchIn(g) -> QuickRequest.Volume(amount)
            Regex("내려|줄여|작게|낮춰|내리|다운").containsMatchIn(g) -> QuickRequest.Volume(-amount)
            else -> null
        }
    }

    private val koreanNumber = mapOf("한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8,
        "아홉" to 9, "열" to 10, "열한" to 11, "열두" to 12, "십" to 10, "이십" to 20, "삼십" to 30, "사십" to 40, "오십" to 50)

    private fun number(word: String): Int? = word.toIntOrNull() ?: koreanNumber[word]

    private fun timer(goal: String): QuickRequest? {
        if (!Regex("타이머|뒤에\\s*알려|후에\\s*알려").containsMatchIn(goal) || Regex("꺼|취소|멈춰|정지|삭제").containsMatchIn(goal)) return null
        var seconds = 0
        Regex("(\\d+|[가-힣]+?)\\s*시간").find(goal)?.let { m -> number(m.groupValues[1])?.let { seconds += it * 3600 } }
        Regex("(\\d+|[가-힣]+?)\\s*분").find(goal)?.let { m -> number(m.groupValues[1])?.let { seconds += it * 60 } }
        Regex("(\\d+|[가-힣]+?)\\s*초").find(goal)?.let { m -> number(m.groupValues[1])?.let { seconds += it } }
        if (Regex("반").containsMatchIn(goal) && Regex("시간\\s*반").containsMatchIn(goal)) seconds += 1800
        return seconds.takeIf { it in 1..86_399 }?.let { QuickRequest.Timer(it) }
    }

    private fun alarm(goal: String): QuickRequest? {
        if (!Regex("알람|깨워").containsMatchIn(goal) || !Regex("맞춰|설정|해\\s*줘|깨워|추가").containsMatchIn(goal) ||
            Regex("꺼|취소|삭제|지워|화면|목록|보여").containsMatchIn(goal)) return null
        val m = Regex("(\\d{1,2}|[가-힣]+?)\\s*시(?:\\s*(\\d{1,2}|반)\\s*분?)?").find(goal) ?: return null
        var hour = number(m.groupValues[1]) ?: return null
        val minute = when (val mm = m.groupValues[2]) { "" -> 0; "반" -> 30; else -> mm.toIntOrNull() ?: 0 }
        val pm = Regex("오후|저녁|밤").containsMatchIn(goal)
        val am = Regex("오전|아침|새벽").containsMatchIn(goal)
        if (pm && hour in 1..11) hour += 12
        if (am && hour == 12) hour = 0
        return if (hour in 0..23 && minute in 0..59) QuickRequest.Alarm(hour, minute) else null
    }

    /** "회사까지 얼마나 걸려?" → "회사": a travel-time question about a place (answered from the route preview). */
    fun etaTarget(goal: String): String? =
        Regex("^(.+?)\\s*(?:까지|에)\\s*(?:가려면\\s*)?(?:차로\\s*)?(?:얼마나|몇\\s*분|시간\\s*얼마나|몇\\s*시간)\\s*(?:걸려|걸리|소요)").find(goal.trim())
            ?.groupValues?.get(1)?.trim()?.takeIf { it.length in 1..20 }

    /** "지금 나오는 노래 뭐야?" */
    fun asksNowPlaying(goal: String): Boolean =
        Regex("(?:지금|이|현재).{0,6}(?:노래|곡|음악).{0,6}(?:뭐|제목|누구)|무슨\\s*(?:노래|곡)").containsMatchIn(goal)

    /** A question or short request that the "say what to do" filter must let through. */
    fun isQuestion(goal: String): Boolean =
        Regex("뭐야|뭐지|몇|얼마|언제|어디|누구|무슨|어때|있어\\?|남았|\\?").containsMatchIn(goal)
}
