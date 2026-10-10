package dev.localphone.core

/**
 * What the last answers were about, for a few minutes: "이담한정식까지 얼마나 걸려?" then "거기로 안내해줘",
 * "알람 일정 알려줘" then "두 번째 거 꺼줘". A reference is rewritten into the plain sentence the rules
 * already handle ("이담한정식으로 안내해줘", "오전 6시 55분 알람 꺼줘"); when it is unclear what it points
 * to, the user is asked instead of guessing.
 */
class ConversationMemory(private val now: () -> Long = System::currentTimeMillis, private val ttlMs: Long = 5 * 60_000L) {
    sealed interface Resolution {
        data object Same : Resolution
        data class Rewritten(val sentence: String, val meaning: String) : Resolution
        data class Unclear(val question: String) : Resolution
    }

    private enum class Kind { PLACE, SONG, ALARM }
    private data class Item(val kind: Kind, val value: String, val at: Long)

    private val items = mutableMapOf<Kind, Item>()
    /** The alarm list in the order it was said, as times the alarm commands understand. */
    private var alarmList: Pair<List<AlarmRow>, Long>? = null

    @Synchronized fun rememberPlace(name: String) = remember(Kind.PLACE, name)
    @Synchronized fun rememberSong(title: String) = remember(Kind.SONG, title)
    /** [phrase]: how to name it again ("다음 알람", "오전 6시 55분 알람"). */
    @Synchronized fun rememberAlarm(phrase: String) = remember(Kind.ALARM, phrase)
    @Synchronized fun rememberAlarmList(rows: List<AlarmRow>) {
        alarmList = rows to now()
        if (rows.isNotEmpty()) remember(Kind.ALARM, "")  // "그거" after a list means an alarm, but which one is asked
    }

    private fun remember(kind: Kind, value: String) { items[kind] = Item(kind, value.trim(), now()) }
    private fun fresh(kind: Kind) = items[kind]?.takeIf { now() - it.at <= ttlMs }

    /** Learns from a finished command and what was said back. */
    @Synchronized fun observe(command: String, result: AgentResult) {
        if (result.outcome != Outcome.DONE) return
        val say = result.say
        Regex("^(.+?)(?:으로|로) 안내를 시작했어요").find(say)?.let { rememberPlace(it.groupValues[1]); return }
        Regex("^(.+?)까지 차로 약").find(say)?.let { rememberPlace(it.groupValues[1]); return }
        (ShortcutGoals.navigationTarget(command) ?: QuickCommands.etaTarget(command))?.takeIf { !refersToPlace(it) }?.let { rememberPlace(it); return }
        Regex("'(.+?)'(?:\\s*나오고|에서\\s*멈춰)").find(say)?.let { rememberSong(it.groupValues[1]); return }
        Regex("^(.+?) 노래를 틀었어요").find(say)?.let { rememberSong(it.groupValues[1]); return }
        (GoalText.playlistAdd(command, true) ?: GoalText.playlistRemove(command, true) ?: GoalText.playSong(command))
            ?.takeIf { !refersToSong(it) }?.let { rememberSong(it) }
    }

    private val placeRef = Regex("(아까\\s*)?(거기|그곳|그\\s*(?:장소|곳|가게|식당|매장|집))(으로|로|까지|에|를|을)?")
    private val songRef = Regex("(그|이|방금|아까)\\s*(노래|곡)")
    private val alarmRef = Regex("(그|이|방금|아까)\\s*알람")
    private val thatRef = Regex("^(?:그거|그것|그걸|저거|이거)\\s*(?:을|를)?\\s+")
    private val ordinal = Regex("(첫|두|세|네|다섯|여섯|일곱|여덟|마지막)\\s*(?:번째|째)?\\s*(?:거|것|알람)")
    private val dayRef = Regex("(평일|주말|매일|[월화수목금토일]요일)?\\s*(오전|오후|아침|저녁)?\\s*(?:거|것|알람)\\s*(?:을|를)?\\s")

    private fun refersToPlace(text: String) = placeRef.containsMatchIn(text)
    private fun refersToSong(text: String) = songRef.containsMatchIn(text)

    @Synchronized fun resolve(goal: String): Resolution {
        val g = goal.trim()
        // "그거 꺼줘": whatever the last answer was about.
        thatRef.find(g)?.let { m ->
            val last = Kind.entries.mapNotNull(::fresh).maxByOrNull { it.at } ?: return Resolution.Unclear("무엇을 말씀하시는지 다시 말씀해 주세요.")
            val rest = g.substring(m.range.last + 1)
            return when (last.kind) {
                Kind.ALARM -> resolve("그 알람 $rest")
                Kind.SONG -> resolve("그 노래 $rest")
                Kind.PLACE -> resolve("거기 $rest")
            }
        }
        if (Regex("알람|거\\s|것\\s").containsMatchIn("$g ") && (fresh(Kind.ALARM) != null || alarmList != null)) alarm(g)?.let { return it }
        placeRef.find(g)?.let { m ->
            val place = fresh(Kind.PLACE)?.value ?: return Resolution.Unclear("어느 곳을 말씀하시는지 장소 이름으로 다시 말씀해 주세요.")
            val particle = when (m.groupValues[3]) { "으로", "로" -> withRo(place); "" -> place; else -> place + m.groupValues[3] }
            return Resolution.Rewritten(g.replaceRange(m.range, particle).replace(Regex("\\s+"), " ").trim(), "${m.value.trim()} → $place")
        }
        songRef.find(g)?.let { m ->
            // "이 노래 제목이 뭐야" is a question about the player, not a reference.
            if (!Regex("틀어|재생|들려|추가|넣어|담아|빼|지워|삭제").containsMatchIn(g)) return Resolution.Same
            val song = fresh(Kind.SONG)?.value ?: return Resolution.Unclear("어느 노래인지 제목으로 다시 말씀해 주세요.")
            return Resolution.Rewritten(g.replaceRange(m.range, song).trim(), "${m.value} → $song")
        }
        return Resolution.Same
    }

    private fun alarm(g: String): Resolution? {
        val list = alarmList?.takeIf { now() - it.second <= ttlMs }?.first.orEmpty()
        fun phrase(row: AlarmRow) = "${AlarmCommands.clock(row.hour, row.minute)} 알람"
        fun rewrite(range: IntRange, row: AlarmRow, what: String) =
            Resolution.Rewritten(g.replaceRange(range, phrase(row) + " ").replace(Regex("\\s+"), " ").trim(), "$what → ${AlarmCommands.describe(row)}")
        ordinal.find(g)?.let { m ->
            if (list.isEmpty()) return Resolution.Unclear("먼저 알람 일정을 들은 뒤에 몇 번째인지 말씀해 주세요.")
            val index = when (m.groupValues[1]) { "첫" -> 0; "두" -> 1; "세" -> 2; "네" -> 3; "다섯" -> 4; "여섯" -> 5; "일곱" -> 6; "여덟" -> 7; else -> list.lastIndex }
            val row = list.getOrNull(index) ?: return Resolution.Unclear("알람은 ${list.size}개예요. 몇 번째인지 다시 말씀해 주세요.")
            return rewrite(m.range, row, m.value.trim())
        }
        alarmRef.find(g)?.let { m ->
            val phrase = fresh(Kind.ALARM)?.value?.takeIf { it.isNotEmpty() }
                ?: return Resolution.Unclear("어느 알람인지 시간으로 말씀해 주세요. 예: 7시 50분 알람 꺼줘")
            return Resolution.Rewritten(g.replaceRange(m.range, phrase).trim(), "${m.value} → $phrase")
        }
        // "일요일 오후 거 꺼줘": picked from the list just said, when exactly one fits.
        if (list.isNotEmpty() && AlarmCommands.time(g) == null) dayRef.find("$g ")?.takeIf { it.groupValues[1].isNotEmpty() || it.groupValues[2].isNotEmpty() }?.let { m ->
            val days = AlarmCommands.days(m.groupValues[1])
            val pm = when (m.groupValues[2]) { "오후", "저녁" -> true; "오전", "아침" -> false; else -> null }
            val fits = list.filter { r -> (days.isEmpty() || days.all { it in r.days } || m.groupValues[1].let { d -> r.date?.contains(d) == true }) &&
                (pm == null || (r.hour >= 12) == pm) }
            return when (fits.size) {
                1 -> rewrite(m.range.first until minOf(m.range.last, g.length), fits[0], m.value.trim())
                0 -> Resolution.Unclear("말씀하신 알람이 목록에 없어요. 시간으로 말씀해 주세요.")
                else -> Resolution.Unclear("해당하는 알람이 ${fits.size}개예요: ${fits.joinToString(", ") { AlarmCommands.describe(it) }}. 시간으로 말씀해 주세요.")
            }
        }
        return null
    }

    private fun withRo(word: String): String {
        val c = word.last()
        val batchim = c in '가'..'힣' && (c - '가') % 28 != 0 && (c - '가') % 28 != 8
        return word + if (batchim) "으로" else "로"
    }

    companion object {
        /** The assistant's memory between commands (one person, one phone). */
        val shared = ConversationMemory()
    }
}
