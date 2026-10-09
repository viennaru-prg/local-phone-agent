package dev.localphone.core

/**
 * Light Korean goal analysis used to steer a small model: which words name the target, and which
 * notes are relevant. No command parsing — the model still decides every action.
 */
object GoalText {
    private val endings = listOf("해주세요", "해줄래", "해줘", "해봐", "시켜줘", "틀어줘", "켜줘", "꺼줘", "가줘", "가자", "줘", "해",
        // Single-syllable particles that also end common nouns (지도, 와이파이, 휴가) are deliberately absent.
        "으로", "에서", "에게", "한테", "부터", "까지", "로", "을", "를", "에", "좀")
    private val stop = setOf("좀", "그", "이거", "저거", "지금", "빨리", "해줘", "줘", "열어", "열어줘", "켜", "꺼", "틀어", "눌러", "눌러줘", "실행", "해봐")

    fun normalize(s: String) = s.lowercase().replace(Regex("[\\s.,!?~·'\"()\\[\\]]+"), "")

    /**
     * Consonant skeleton for comparing a spoken Korean name with a Latin app name:
     * "클립스트림" and "Clipstream" both become "KLPSTLM". Vowels and ㅇ/h are dropped, l/r merge.
     */
    fun skeleton(s: String): String {
        val out = StringBuilder()
        fun add(c: Char) { if (out.lastOrNull() != c) out.append(c) }
        val initial = "KKNTTLMPPSSXSSSKTPX" // ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ
        val final = " KKKNNNTLKMLLLPLMPPSSXSSKTPX" // none ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ
        val latin = s.lowercase().replace("ph", "p").replace("ch", "s").replace("sh", "s").replace("th", "t")
            .replace("ck", "k").replace("ng", "").replace("x", "ks").replace("qu", "k")
        for (c in latin) when (c) {
            in '가'..'힣' -> {
                val i = c - '가'
                initial[i / 588].takeIf { it != 'X' }?.let(::add)
                final[i % 28].takeIf { it != ' ' && it != 'X' }?.let(::add)
            }
            'k', 'g', 'q', 'c' -> add('K'); 't', 'd' -> add('T'); 'p', 'b', 'f', 'v' -> add('P')
            's', 'z', 'j' -> add('S'); 'l', 'r' -> add('L'); 'm' -> add('M'); 'n' -> add('N')
            else -> {}
        }
        return out.toString()
    }

    /** "클립스트림" ~ "Clipstream Player": the same name written in Hangul or Latin letters. */
    fun soundsLike(spoken: String, label: String): Boolean {
        val wanted = skeleton(spoken)
        if (wanted.length < 4) return false
        val words = label.trim().split(Regex("\\s+"))
        return words.indices.any { start -> (start until words.size).any { end -> skeleton(words.subList(start, end + 1).joinToString("")) == wanted } }
    }

    /** "회사로 안내해줘" -> [회사, 안내]; "음악 재생해줘" -> [음악, 재생]. */
    fun words(goal: String): List<String> = goal.split(Regex("\\s+")).mapNotNull { raw ->
        var w = normalize(raw)
        repeat(2) { endings.firstOrNull { w.length > it.length && w.endsWith(it) }?.let { w = w.dropLast(it.length) } }
        w.takeIf { it.length >= 2 && it !in stop }
    }.distinct()

    private val chatter = Regex("^(응|어|네|예|아니|아니요|아뇨|그래|그래요|좋아|좋아요|오케이|ok|okay|고마워|고마워요|고맙습니다|감사합니다|알았어|알겠어|알겠습니다|됐어|맞아|음|아|잠깐|뭐야|진짜|그렇구나|수고했어)$")
    private val pastTense = Regex("(었어|았어|했어|켰어|껐어|됐어|봤어|왔어)$")

    /**
     * True for utterances that ask for nothing: acknowledgements, thanks, or a bare past-tense report
     * ("켰어"). Those reached the agent when the microphone caught nearby speech.
     */
    fun isChatter(goal: String): Boolean {
        val tokens = goal.trim().split(Regex("\\s+")).map(::normalize).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return true
        return tokens.all { chatter.matches(it) || (pastTense.containsMatchIn(it) && it.length <= 4) }
    }

    /** "네이버 지도에서 카페 검색해줘" → "카페"; "아이유 노래 검색해줘" → "아이유 노래". */
    fun searchQuery(goal: String): String? {
        val m = Regex("^(?:.*?에서\\s*)?(.+?)\\s*(?:을|를)?\\s*(?:검색|찾아)").find(goal.trim()) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() && it.length <= 40 }
    }

    /** Goals that are about reaching a screen ("배터리 화면 열어줘", "타이머 탭 보여줘"). */
    fun opensScreen(goal: String) = Regex("열어|보여|들어가|화면|페이지|메뉴|탭").containsMatchIn(goal)

    /**
     * Words naming the target, without the app part: "네이버 지도에서 카페 검색해줘" → [카페, 검색].
     * Otherwise every "지도" label on the map would look like the target.
     */
    fun targetWords(goal: String): List<String> {
        val afterApp = Regex("^.+?에서\\s+(.+)$").find(goal.trim())?.groupValues?.get(1) ?: goal
        val operation = if (Regex("꺼|종료|끝내|중지|그만").containsMatchIn(afterApp)) listOf("종료", "끝내", "중지", "꺼짐", "해제") else emptyList()
        return (words(afterApp).ifEmpty { words(goal) } + operation).distinct()
    }

    /**
     * A short sentence to speak when a task is done. The model's own wording (its completion check)
     * tends to be a long explanation; spoken results should be a few words.
     */
    fun spokenResult(goal: String, modelSay: String): String {
        ShortcutGoals.literalSearch(goal)?.let { return "'$it' 검색했어요." }
        if (ShortcutGoals.screenName(goal) != null || DirectGoals.appName(goal) != null) {
            val names = targetWords(goal).filter { it !in setOf("화면", "메뉴", "탭", "페이지", "보여", "열어", "앱") }
            if (names.isNotEmpty()) {
                val what = goal.split(Regex("\\s+")).filter { w -> names.any { normalize(w).startsWith(it) } }
                    .joinToString(" ") { it.replace(Regex("(을|를|에서)$"), "") }.ifBlank { names.joinToString(" ") }
                return if (Regex("화면|메뉴|탭|페이지").containsMatchIn(goal)) "$what 화면을 열었어요." else "$what 열었어요."
            }
        }
        val text = modelSay.trim()
        if (ShortcutGoals.navigationTarget(goal) != null) return text.split(Regex("(?<=[.!?。])\\s+")).first().ifBlank { "완료했어요." }
        return if (text.isNotEmpty()) text.take(360) else "완료했어요."
    }

    /** Spoken Korean term → how Android usually labels it (and back). */
    private val synonyms = listOf(
        "와이파이" to "wi-fi", "와이파이" to "wifi", "블루투스" to "bluetooth", "핫스팟" to "hotspot", "디스플레이" to "display",
        "배터리" to "battery", "알림" to "notification", "개발자옵션" to "developer",
    ).flatMap { (k, v) -> listOf(k to normalize(v), normalize(v) to k) }.groupBy({ it.first }, { it.second })

    /** [word] plus its Korean/English counterpart ("와이파이" → also "wi-fi"). */
    fun variants(word: String): List<String> = listOf(word) + synonyms[word].orEmpty()

    fun matches(label: String, words: List<String>): Boolean {
        val l = normalize(label)
        return l.isNotEmpty() && words.any { w -> variants(w).any { l.contains(it) } }
    }

    /**
     * "클립스트림에서 아이유 좋은날 재생목록에 추가해줘" -> "아이유 좋은날": the song to search and add to
     * the current playlist. Null for anything that is not "<song> (재생목록에) 추가/넣어/담아 줘".
     */
    fun playlistAdd(goal: String): String? {
        val g = goal.trim().let { if (namedApp(it) != null) it.substringAfter("에서").trim() else it }
        val m = Regex("^(.+?)\\s*(?:(?:현재\\s*)?(?:재생\\s*목록|플레이\\s*리스트)\\s*에?\\s*)?(?:추가|넣어|담아)\\s*(?:해\\s*)?(?:줘|주세요|줄래)?\\s*[.!?]?$")
            .matchEntire(g) ?: return null
        val query = m.groupValues[1].trim().replace(Regex("\\s*(?:을|를)$"), "").replace(Regex("\\s+(?:노래|음악|곡)$"), "").trim()
        return query.takeIf { it.length >= 2 && !Regex("재생\\s*목록|플레이\\s*리스트").containsMatchIn(it) }
    }

    /** How many words of [query] a result row names ("아이유 좋은날" in "아이유(IU) - 좋은 날 [가사]" -> 2 of 2). */
    fun rowScore(query: String, row: String): Int {
        val r = normalize(row)
        return query.trim().split(Regex("\\s+")).map(::normalize).filter { it.isNotEmpty() }.count { r.contains(it) || soundsLike(it, row) }
    }

    /** The app named in "X에서 …" ("시계 앱에서 …" → "시계"), if any. */
    fun namedApp(goal: String): String? =
        Regex("^(.+?)\\s*(?:앱)?에서\\s").find(goal.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 20 }

    /**
     * Notes may start with "[키워드, 키워드]". Tagged notes are shown only when the goal mentions one
     * of the keywords; untagged notes are always shown.
     */
    fun relevantNotes(goal: String, notes: List<String>): List<String> {
        val g = normalize(goal)
        // "유튜브에서 아이유 노래 검색해줘" names its app: a note steering "노래" to another app must not apply.
        val app = namedApp(goal)?.let(::normalize)
        return notes.mapNotNull { line ->
            val tag = Regex("^\\[(.*?)]\\s*(.*)$").matchEntire(line.trim())
            if (tag == null) line.trim()
            else if (tag.groupValues[1].split(',').map { normalize(it) }.any { it.isNotEmpty() && g.contains(it) } &&
                (app == null || normalize(tag.groupValues[2]).contains(app))) tag.groupValues[2]
            else null
        }
    }
}
