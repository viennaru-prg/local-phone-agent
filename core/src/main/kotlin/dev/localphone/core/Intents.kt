package dev.localphone.core

/**
 * The model reads a command no rule recognized ("수요모임 가는 길 좀 알려줄래") and names what it asks
 * for; that is rewritten into the plain sentence the rules and skills already handle ("수요모임으로
 * 안내해줘"). Commands the rules recognize never come here, so they keep their speed. The rewrite is
 * used only if the rules recognize it and its names were really said; otherwise the command goes to
 * the screen agent unchanged, as before.
 */
object Intents {
    const val SYSTEM = "사용자의 음성 명령이 무엇을 요청하는지 하나만 고른다. target과 app은 명령에 나온 말을 그대로 쓴다.\n" +
        "intent: navigate(목적지로 길안내), eta(목적지까지 걸리는 시간), end_navigation(길안내 종료), " +
        "play_song(특정 노래 재생), media(play/pause/next/previous: 음악 재생·멈춤·다음 곡·이전 곡), now_playing(지금 나오는 노래), " +
        "playlist_add(재생목록에 노래 추가), playlist_remove(재생목록에서 노래 빼기), open_app(앱 열기), " +
        "open_screen(앱 안의 화면·메뉴 열기), search(앱에서 검색), none(그 밖의 일)\n" +
        "예:\n" +
        "수요모임 가는 길 좀 알려줄래 → {\"intent\":\"navigate\",\"target\":\"수요모임\",\"app\":\"\"}\n" +
        "회사 가려면 몇 분이나 걸려 → {\"intent\":\"eta\",\"target\":\"회사\",\"app\":\"\"}\n" +
        "길 안내 이제 그만해 → {\"intent\":\"end_navigation\",\"target\":\"\",\"app\":\"\"}\n" +
        "더 크로스 노래 듣고 싶어 → {\"intent\":\"play_song\",\"target\":\"더 크로스\",\"app\":\"\"}\n" +
        "노래 잠깐 멈춰봐 → {\"intent\":\"media\",\"target\":\"pause\",\"app\":\"\"}\n" +
        "이거 무슨 노래야 → {\"intent\":\"now_playing\",\"target\":\"\",\"app\":\"\"}\n" +
        "좋은날 플레이리스트에 넣어 → {\"intent\":\"playlist_add\",\"target\":\"좋은날\",\"app\":\"\"}\n" +
        "설정 들어가서 와이파이 좀 보자 → {\"intent\":\"open_screen\",\"target\":\"와이파이\",\"app\":\"설정\"}\n" +
        "네이버 지도에서 카페 좀 찾아봐 → {\"intent\":\"search\",\"target\":\"카페\",\"app\":\"네이버 지도\"}\n" +
        "카카오톡에서 엄마한테 온 메시지 읽어줘 → {\"intent\":\"none\",\"target\":\"\",\"app\":\"\"}"

    private val names = listOf("navigate", "eta", "end_navigation", "play_song", "media", "now_playing",
        "playlist_add", "playlist_remove", "open_app", "open_screen", "search", "none")

    val GRAMMAR = "root ::= \"{\\\"intent\\\":\\\"\" intent \"\\\",\\\"target\\\":\\\"\" text \"\\\",\\\"app\\\":\\\"\" text \"\\\"}\"\n" +
        "intent ::= " + names.joinToString(" | ") { "\"$it\"" } + "\n" +
        "text ::= [^\"\\\\]{0,30}\n"

    fun prompt(goal: String) = ModelPrompt(SYSTEM, "$goal →")

    /**
     * Worth asking: a sentence no rule recognized, including wishes the request words miss ("늙은 사랑
     * 듣고 싶어", "…볼래"). Bare names ("이름 모임") and chatter are answered as before.
     */
    fun worthAsking(goal: String) = ModelUse.likely(goal) && !GoalText.isChatter(goal) &&
        (GoalText.hasRequest(goal) || QuickCommands.isQuestion(goal) || sentence.containsMatchIn(goal.trim()))

    private val sentence = Regex("(?:싶어|싶은데|싶다|볼래|줄래|할래|해봐|봐|줘|어|아|요|래|까|지|니|냐)[.!?~]*$")

    /** The model's answer as the sentence the rules handle, or null to keep the command as it was. */
    fun rewrite(goal: String, raw: String): String? {
        val json = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val intent = json.get("intent")?.asString ?: return null
        val target = json.get("target")?.asString.orEmpty().trim()
        val app = json.get("app")?.asString.orEmpty().trim()
        // Names must have been said: the model may only pick out words, never supply them.
        val said = GoalText.normalize(goal)
        fun spoken(text: String) = text.isNotEmpty() && GoalText.normalize(text).let { it.isNotEmpty() && said.contains(it) }
        val sentence = when (intent) {
            "navigate" -> target.takeIf(::spoken)?.let { "${withRo(it)} 안내해줘" }
            "eta" -> target.takeIf(::spoken)?.let { "${it}까지 얼마나 걸려" }
            "end_navigation" -> "내비게이션 꺼줘"
            "play_song" -> target.takeIf(::spoken)?.let { "$it 틀어줘" }
            "media" -> when (target.lowercase()) {
                "play" -> "음악 틀어줘"; "pause" -> "음악 멈춰줘"; "next" -> "다음 곡 틀어줘"; "previous" -> "이전 곡 틀어줘"; else -> null
            }
            "now_playing" -> "지금 나오는 노래 뭐야"
            "playlist_add" -> target.takeIf(::spoken)?.let { "재생목록에 $it 추가해줘" }
            "playlist_remove" -> target.takeIf(::spoken)?.let { "재생목록에서 $it 빼줘" }
            "open_app" -> (app.takeIf(::spoken) ?: target.takeIf(::spoken))?.let { "$it 열어줘" }
            "open_screen" -> if (spoken(app) && spoken(target)) "${app}에서 $target 화면 열어줘" else null
            "search" -> if (spoken(app) && spoken(target)) "${app}에서 $target 검색해줘" else null
            else -> null
        } ?: return null
        // Used only when it lands on a rule; a sentence the rules do not take is no better than the original.
        return sentence.takeIf { !ModelUse.likely(it) }
    }

    private fun withRo(word: String): String {
        val c = word.last()
        val batchim = c in '가'..'힣' && (c - '가') % 28 != 0 && (c - '가') % 28 != 8
        return word + if (batchim) "으로" else "로"
    }
}
