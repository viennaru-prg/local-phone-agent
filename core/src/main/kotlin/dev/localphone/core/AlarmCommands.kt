package dev.localphone.core

import java.util.Calendar

/** Which alarm a command means: the one that rings next, or the one at a time ("7시 알람"). */
sealed interface AlarmTarget {
    data object Next : AlarmTarget
    data class At(val hour: Int, val minute: Int) : AlarmTarget
}

/**
 * Alarm questions and changes beyond setting one (which is [QuickRequest.Alarm]): the next alarm, the
 * whole list, switching one off or on (or off just once), changing its sound.
 */
sealed interface AlarmRequest {
    data object Next : AlarmRequest
    data object List : AlarmRequest
    /** [once]: skip only the coming ring of a repeating alarm; it is switched back on right after. */
    data class Switch(val target: AlarmTarget, val on: Boolean, val once: Boolean) : AlarmRequest
    /** Deletes one alarm; several at that time are asked about, never guessed. */
    data class Delete(val target: AlarmTarget) : AlarmRequest
    /** [name]: the sound to pick ("Over the Horizon으로"), null to open the choice for the user. */
    data class Sound(val target: AlarmTarget, val name: String?) : AlarmRequest
}

/** One alarm in the clock app's list, read from its switch ("테스트알람, 오전 04:44, 매일"). */
data class AlarmRow(val id: Int, val label: String?, val hour: Int, val minute: Int, val days: List<Int>, val on: Boolean,
                    val holidaysOff: Boolean = false, val date: String? = null)

object AlarmCommands {
    fun parse(goal: String): AlarmRequest? {
        if (!Regex("알람|알림\\s*소리").containsMatchIn(goal) || GoalScope.multiple(goal)) return null
        val target = time(goal)?.let { (h, m) -> AlarmTarget.At(h, m) } ?: AlarmTarget.Next
        if (Regex("소리|음악|노래|벨소리|알람음|음원").containsMatchIn(goal) && Regex("바꿔|변경|교체|바꾸|설정").containsMatchIn(goal))
            return AlarmRequest.Sound(target, soundName(goal))
        if (Regex("지워|삭제|없애").containsMatchIn(goal)) return AlarmRequest.Delete(target)
        val off = Regex("꺼|끄|해제|취소|울리지|안\\s*울리|건너뛰|스킵").containsMatchIn(goal)
        val on = !off && Regex("켜|다시\\s*울리|살려").containsMatchIn(goal)
        if (off || on) {
            // "다음 알람 꺼줘" means the coming ring; "7시 알람 꺼줘" switches that alarm off for good unless
            // the command says once ("이번만", "내일만", "한 번만").
            val once = off && (target == AlarmTarget.Next || Regex("이번|한\\s*번|내일만|오늘만|하루만|건너뛰|스킵").containsMatchIn(goal))
            return AlarmRequest.Switch(target, on, once)
        }
        if (Regex("다음\\s*알람|알람.{0,6}(?:언제|몇\\s*시)").containsMatchIn(goal) && !Regex("일정|목록|전부|모두|다\\s*(?:알려|보여)").containsMatchIn(goal))
            return AlarmRequest.Next
        if (Regex("일정|목록|리스트|뭐|몇\\s*개|있어|있나|알려|보여|맞춰져|설정돼|설정되어").containsMatchIn(goal)) return AlarmRequest.List
        return null
    }

    /** "7시 반", "오후 2시 20분", "6:30" → 24-hour time; null when the command names no time. */
    fun time(goal: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})\\s*:\\s*(\\d{2})").find(goal)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: Regex("(\\d{1,2}|[가-힣]{1,2}?)\\s*시(?:\\s*(\\d{1,2}|반)\\s*분?)?").find(goal)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: return null
        var hour = QuickCommands.number(m.first) ?: return null
        val minute = when (m.second) { "" -> 0; "반" -> 30; else -> m.second.toIntOrNull() ?: return null }
        if (Regex("오후|저녁|밤").containsMatchIn(goal) && hour in 1..11) hour += 12
        if (Regex("오전|아침|새벽").containsMatchIn(goal) && hour == 12) hour = 0
        return (hour to minute).takeIf { hour in 0..23 && minute in 0..59 }
    }

    private fun soundName(goal: String): String? =
        Regex("(?:을|를|은|는)?\\s*([^\\s].{0,30}?)\\s*(?:으로|로)\\s*(?:바꿔|변경|교체|바꾸|설정)").find(goal)?.groupValues?.get(1)?.trim()
            ?.replace(Regex("^.*?(?:소리|음악|노래|벨소리|알람음)\\s*(?:을|를)?\\s*"), "")?.trim()
            ?.takeIf { it.isNotEmpty() && !Regex("^(?:다른|딴|새|다른\\s*거|다른\\s*노래|다른\\s*소리)$").matches(it) }

    private val dayNames = linkedMapOf('일' to Calendar.SUNDAY, '월' to Calendar.MONDAY, '화' to Calendar.TUESDAY,
        '수' to Calendar.WEDNESDAY, '목' to Calendar.THURSDAY, '금' to Calendar.FRIDAY, '토' to Calendar.SATURDAY)

    /** Repeat days a command or a list row names: 매일, 평일, 주말, "월수금", "화요일, 목요일". Empty for once. */
    fun days(text: String): List<Int> {
        // A dated one-time alarm ("10월 10일 토요일") repeats on no day.
        if (date.containsMatchIn(text)) return emptyList()
        if (Regex("매일|날마다").containsMatchIn(text)) return (1..7).toList()
        if (Regex("평일|주중").containsMatchIn(text)) return (2..6).toList()
        if (Regex("주말").containsMatchIn(text)) return listOf(1, 7)
        // "월수금", "월·수·금", "화요일, 목요일", "토요일마다": runs of day letters before 요일/마다/에 or a separator.
        val found = linkedSetOf<Int>()
        Regex("(?<![가-힣])([월화수목금토일](?:\\s*[,·/와과및]?\\s*[월화수목금토일])*)\\s*(?:요일|마다|에|\\s|$|,)").findAll(text).forEach { m ->
            if (m.groupValues[0].contains("요일") || m.groupValues[1].length > 1 || Regex("매주|마다").containsMatchIn(text))
                m.groupValues[1].filter { it in dayNames }.forEach { found += dayNames.getValue(it) }
        }
        return found.sorted()
    }

    /** The clock app's list: each alarm's switch says its name, time and days. */
    fun rows(view: ScreenView): List<AlarmRow> = if (selecting(view)) emptyList() else view.elements.filter { it.kind == Kind.SWITCH }.mapNotNull { e ->
        val m = Regex("(오전|오후|AM|PM)\\s*(\\d{1,2}):(\\d{2})", RegexOption.IGNORE_CASE).find(e.label) ?: return@mapNotNull null
        var hour = m.groupValues[2].toInt() % 12
        if (m.groupValues[1] == "오후" || m.groupValues[1].equals("PM", true)) hour += 12
        val label = e.label.substring(0, m.range.first).trim().trimEnd(',').trim().ifEmpty { null }
        val rest = e.label.substring(m.range.last + 1)
        AlarmRow(e.id, label, hour, m.groupValues[3].toInt(), days(rest), e.checked == true, rest.contains("공휴일에는 끄기"),
            date.find(rest)?.value?.let { d -> "${d.trim()}${Regex("[월화수목금토일]요일").find(rest.substringAfter(d))?.value?.let { " $it" }.orEmpty()}" })
    }

    /** The list's selection mode ("1개 선택됨"): its switches mark selected rows, not alarms that are on. */
    fun selecting(view: ScreenView): Boolean = view.elements.any {
        // Scrolled, the header shrinks to a bare count ("1"); its "모두 선택" box is always there.
        Regex("^\\d+개 선택됨$").matches(it.label.trim()) || (it.kind == Kind.SWITCH && it.label.trim() == "모두 선택")
    }

    /** "평일 오전 6시 30분", "토요일 오전 7시 30분", "'운동' 매일 오후 6시". */
    fun describe(row: AlarmRow): String =
        listOfNotNull(row.label?.let { "'$it'" }, row.date ?: dayWords(row.days), clock(row.hour, row.minute)).joinToString(" ")

    private val date = Regex("\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일")

    /** The spoken list: the alarms that will ring, and how many are off. */
    fun summary(rows: List<AlarmRow>): String {
        if (rows.isEmpty()) return "맞춰 둔 알람이 없어요."
        val on = rows.filter { it.on }
        val off = rows.size - on.size
        val offText = if (off > 0) " 꺼진 알람이 ${off}개 더 있어요." else ""
        if (on.isEmpty()) return "켜진 알람이 없어요.$offText"
        return "켜진 알람은 ${on.size}개예요. ${on.joinToString(", ") { describe(it) }}.$offText"
    }

    /** The next ring: "내일 오전 7시 50분이에요. 11시간 33분 남았어요." */
    fun next(nowMillis: Long, atMillis: Long?): String {
        if (atMillis == null) return "예정된 알람이 없어요."
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val at = Calendar.getInstance().apply { timeInMillis = atMillis }
        val dayGap = ((startOfDay(at) - startOfDay(now)) / 86_400_000L).toInt()
        val day = when (dayGap) {
            0 -> "오늘"; 1 -> "내일"; 2 -> "모레"
            else -> "${at.get(Calendar.MONTH) + 1}월 ${at.get(Calendar.DAY_OF_MONTH)}일 ${dayLetter(at.get(Calendar.DAY_OF_WEEK))}요일"
        }
        val left = (atMillis - nowMillis) / 60_000L
        val leftText = listOfNotNull((left / 60).takeIf { it > 0 }?.let { "${it}시간" }, (left % 60).takeIf { it > 0 || left < 60 }?.let { "${it}분" }).joinToString(" ")
        return "다음 알람은 $day ${clock(at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE))}이에요. $leftText 남았어요."
    }

    /** The row of the alarm that rings at [atMillis] (the system's next alarm). */
    fun rowAt(rows: List<AlarmRow>, atMillis: Long): AlarmRow? {
        val at = Calendar.getInstance().apply { timeInMillis = atMillis }
        val same = rows.filter { it.on && it.hour == at.get(Calendar.HOUR_OF_DAY) && it.minute == at.get(Calendar.MINUTE) }
        return same.firstOrNull { at.get(Calendar.DAY_OF_WEEK) in it.days } ?: same.firstOrNull { it.days.isEmpty() } ?: same.firstOrNull()
    }

    fun rowsAt(rows: List<AlarmRow>, hour: Int, minute: Int): List<AlarmRow> = rows.filter { it.hour == hour && it.minute == minute }
        .ifEmpty { if (hour < 12) rows.filter { it.hour == hour + 12 && it.minute == minute } else emptyList() }

    fun clock(hour: Int, minute: Int): String {
        val h = hour % 12
        return "${if (hour < 12) "오전" else "오후"} ${if (h == 0) 12 else h}시${if (minute > 0) " ${minute}분" else ""}"
    }

    fun dayWords(days: List<Int>): String? = when {
        days.isEmpty() -> null
        days.sorted() == (1..7).toList() -> "매일"
        days.sorted() == (2..6).toList() -> "평일"
        days.sorted() == listOf(1, 7) -> "주말"
        days.size == 1 -> "${dayLetter(days[0])}요일"
        else -> days.sortedBy { (it + 5) % 7 }.joinToString("·") { dayLetter(it).toString() }
    }

    private fun dayLetter(day: Int) = dayNames.entries.first { it.value == day }.key
    private fun startOfDay(c: Calendar) = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
