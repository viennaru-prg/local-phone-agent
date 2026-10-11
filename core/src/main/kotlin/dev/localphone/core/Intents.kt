package dev.localphone.core

/**
 * The model reads a command no rule recognized ("수요모임 가는 길 좀 알려줄래") and names what it asks
 * for; that is rewritten into the plain sentence the rules and skills already handle ("수요모임으로
 * 안내해줘"). Commands the rules recognize never come here, so they keep their speed. The rewrite is
 * used only if the rules recognize it and its names were really said; otherwise the command goes to
 * the screen agent unchanged, as before.
 */
object Intents {
    // Kept short: it is decoded cold whenever the model was not in memory (510 tokens took 7 s). The JSON
    // shape comes from the grammar, so the examples only show intent | target | app.
    const val SYSTEM = "음성 명령이 요청하는 일 하나를 고른다. target·app은 명령에 나온 말 그대로, 없으면 빈칸.\n" +
        "navigate 길안내, eta 걸리는 시간, end_navigation 길안내 종료(안내·내비를 말할 때만), close_app 앱 닫기, " +
        "play_song 노래 재생, media play/pause/next/previous, now_playing 지금 노래, playlist_add/playlist_remove 재생목록, " +
        "open_app 앱 열기, open_screen 앱 안 화면, search 앱에서 검색, none 그 밖\n" +
        "수요모임 가는 길 좀 알려줄래 → navigate|수요모임|\n" +
        "회사 가려면 몇 분이나 걸려 → eta|회사|\n" +
        "길 안내 이제 그만해 → end_navigation||\n" +
        "네이버 지도 이제 닫아줄래 → close_app||네이버 지도\n" +
        "더 크로스 노래 듣고 싶어 → play_song|더 크로스|\n" +
        "노래 잠깐 멈춰봐 → media|pause|\n" +
        "설정 들어가서 와이파이 좀 보자 → open_screen|와이파이|설정\n" +
        "네이버 지도에서 카페 좀 찾아봐 → search|카페|네이버 지도\n" +
        "카카오톡에서 엄마한테 온 메시지 읽어줘 → none||"

    private val names = listOf("navigate", "eta", "end_navigation", "close_app", "play_song", "media", "now_playing",
        "playlist_add", "playlist_remove", "open_app", "open_screen", "search", "none")

    val GRAMMAR = "root ::= \"{\\\"intent\\\":\\\"\" intent \"\\\",\\\"target\\\":\\\"\" text \"\\\",\\\"app\\\":\\\"\" text \"\\\"}\"\n" +
        "intent ::= " + names.joinToString(" | ") { "\"$it\"" } + "\n" +
        "text ::= [^\"\\\\]{0,30}\n"

    fun prompt(goal: String) = ModelPrompt(SYSTEM, "$goal →")

    private val conditional = setOf("navigate", "eta", "play_song", "playlist_add", "playlist_remove", "open_screen", "search")
    private val conditions = Regex("들러|들렀|들렸|경유|거쳐|가면서|오면서|하면서|가는\\s*길에|말고|제외|빼고|피해|없이|가장|제일|최단|최소|최대|" +
        "가까운|저렴|싼\\s|이상|이하|중에서|먼저|다음에|부터|만\\s*(?:틀어|보여|찾아)")

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
        // An app the user never named means the model guessed: the whole reading is dropped.
        if (app.isNotEmpty() && !spoken(app)) return null
        // The plain sentences carry a place, a song, a screen, nothing more. A command with a condition
        // ("회사 가면서 주유소 들러줘", "더 크로스 말고", "내일 아침 회사까지") is kept as said: dropping the
        // condition would do something else than asked.
        if (intent in conditional && conditions.containsMatchIn(goal)) return null
        if ((intent == "navigate" || intent == "eta") && Regex("내일|모레|출발|도착|시에|시쯤").containsMatchIn(goal)) return null
        val sentence = when (intent) {
            "navigate" -> target.takeIf(::spoken)?.let { "${withRo(it)} 안내해줘" }
            "eta" -> target.takeIf(::spoken)?.let { "${it}까지 얼마나 걸려" }
            "end_navigation" -> "내비게이션 꺼줘"
            // An app the user did not name is refused, never turned into "close whatever is in front".
            "close_app" -> when {
                app.isEmpty() -> "종료해줘"
                spoken(app) -> "$app 종료해줘"
                else -> null
            }
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
