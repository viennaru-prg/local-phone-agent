package dev.localphone.core

/** Positive, whole-request matches for optimizations. Anything else remains an unrestricted goal. */
object ShortcutGoals {
    fun body(goal: String): String = GoalText.namedApp(goal)?.let {
        goal.substringAfter("에서").trim()
    } ?: goal.trim()

    private val navigation = Regex("^(.+?)(?:으로|로|까지|에)?\\s*(?:길\\s*안내(?:해\\s*줘|해\\s*주세요|해)?|안내(?:해\\s*줘|해\\s*주세요|해)?|가자|가\\s*줘|가요|가는\\s*길\\s*알려\\s*줘)\\s*[.!?]?$" )
    // These are language qualifiers, not an application feature catalog. They require interpretation.
    private val qualified = Regex("그리고|하면서|들러|들렀|경유|말고|제외|피해|피해서|없이|가장|더\\s|가까|저렴|최단|최소|최대|이상|이하|중에서|수정|변경|바꿔|다른|유지|비교|차이|조건|24시간")

    fun navigationTarget(goal: String): String? {
        if (GoalScope.multiple(goal)) return null
        val text = body(goal)
        if (qualified.containsMatchIn(text)) return null
        return navigation.matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 50 }
    }

    /** An explicit screen-opening request, not every sentence containing '보여/화면'. */
    fun screenName(goal: String): String? {
        if (GoalScope.multiple(goal)) return null
        val text = body(goal)
        if (qualified.containsMatchIn(text)) return null
        Regex("^(.+?)\\s*(?:설정\\s*)?(?:화면|메뉴|탭|페이지)\\s*(?:열어|보여|들어가)(?:\\s*줘|\\s*주세요)?\\s*[.!?]?$")
            .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Inside a named app a short place is a screen too: "설정에서 디스플레이 들어가줘", "설정에서 소프트웨어
        // 정보 보여줘". "보여줘" alone also asks for content ("갤러리에서 최근 사진 보여줘"), so with it the
        // name must end in a page noun.
        if (GoalText.namedApp(goal) == null) return null
        val m = Regex("^(.+?)\\s*(열어|들어가|보여)(?:\\s*줘|\\s*주세요)?\\s*[.!?]?$").matchEntire(text) ?: return null
        val name = m.groupValues[1].trim().replace(Regex("(?:을|를|으로|로)$"), "").trim()
        if (name.isEmpty() || name.split(Regex("\\s+")).size > 3) return null
        if (m.groupValues[2] == "보여" && !pageNoun.containsMatchIn(name)) return null
        return name
    }
    private val pageNoun = Regex("(?:정보|설정|목록|기록|내역|관리|사용량|옵션)$")

    /** Search submission only, with no selection/ranking constraint. '찾아줘' is a general task. */
    fun literalSearch(goal: String): String? {
        if (GoalScope.multiple(goal)) return null
        val text = body(goal)
        if (qualified.containsMatchIn(text)) return null
        Regex("^(.+?)\\s*(?:을|를)?\\s*검색(?:\\s*해\\s*줘|\\s*해\\s*주세요|\\s*해|\\s*줘)?\\s*[.!?]?$")
            .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 }?.let { return it }
        // "네이버 지도에서 근처 주유소 찾아줘" is a search inside the named app (the model went to 길찾기).
        if (GoalText.namedApp(goal) == null) return null
        return Regex("^(.+?)\\s*(?:을|를)?\\s*찾아\\s*(?:줘|봐|주세요|줄래)?\\s*[.!?]?$")
            .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 }
    }

    fun searchPrefix(goal: String): String? = literalSearch(GoalScope.parts(goal).first())
    fun allowsUiHeuristics(goal: String) = navigationTarget(goal) != null || screenName(goal) != null || searchPrefix(goal) != null
}
