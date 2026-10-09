package dev.localphone.core

/**
 * "수원 집으로 안내해 주고 노래 틀어 줘" → ["수원 집으로 안내해 줘", "노래 틀어 줘"]: two commands said as one.
 * Split only where the parts are independent ("…주고", "…하고", "그리고", "…한 다음에") and only when
 * every part is a command this assistant handles on its own; a chained task ("검색해서 첫 번째 영상
 *틀어줘") stays whole for the screen agent.
 */
object CommandSplit {
    private val joiner = Regex("\\s*(?:그리고|그\\s*다음에?|그\\s*뒤에?|다음에|한\\s*(?:다음|뒤|후)에?)\\s+|\\s*(주고|하고|켜고|틀고|열고|끄고|멈추고|찾고|꺼주고|켜주고)\\s+")
    // A part that ended in a connective gets its request ending back: "안내해 주" + 고 → "안내해 줘".
    private val restore = mapOf("주고" to "줘", "하고" to "해줘", "켜고" to "켜줘", "틀고" to "틀어줘", "열고" to "열어줘",
        "끄고" to "꺼줘", "멈추고" to "멈춰줘", "찾고" to "찾아줘", "꺼주고" to "꺼줘", "켜주고" to "켜줘")

    fun split(goal: String): List<String>? {
        val parts = mutableListOf<String>()
        var rest = goal.trim()
        while (true) {
            val m = joiner.find(rest) ?: break
            val head = rest.substring(0, m.range.first).trim()
            val connective = m.groupValues[1]
            // "음악 멈추고" keeps its space ("음악 멈춰줘"); "안내하고" stays joined ("안내해줘").
            val spaced = m.value.firstOrNull()?.isWhitespace() == true
            parts += if (connective.isNotEmpty()) head + (if (spaced) " " else "") + restore.getValue(connective) else head
            rest = rest.substring(m.range.last + 1).trim()
        }
        parts += rest
        val steps = parts.map { it.trim() }.filter { it.isNotEmpty() }.map(::foldAfterthought)
        if (steps.size < 2 || steps.size > 3) return null
        return steps.takeIf { it.all(::standsAlone) }
    }

    /** "노래 틀어 줘 노래는 자전거를 탄 풍경": the name said afterwards belongs to the request ("자전거를 탄 풍경 틀어줘"). */
    fun foldAfterthought(part: String): String {
        val m = Regex("^(.*?(?:틀어|재생해|들려)\\s*(?:줘|주세요))\\s+(?:노래|곡|음악)(?:은|는)\\s+(.+?)\\s*(?:이야|야|이요|요)?[.!]?$").matchEntire(part.trim())
            ?: return part
        return "${m.groupValues[2].trim()} 틀어줘"
    }

    /** A command the assistant can carry out without the others' screens. */
    fun standsAlone(step: String): Boolean = GoalText.hasRequest(step) && !GoalScope.multiple(step) && (
        ShortcutGoals.navigationTarget(step) != null || Harness.isEndGuidance(step) ||
            Router.mediaKeyIn(step) != null || GoalText.playSong(step) != null ||
            GoalText.playlistAdd(step) != null || GoalText.playlistRemove(step) != null ||
            DirectGoals.appName(step) != null || DirectGoals.settingsScreen(step) != null ||
            ShortcutGoals.searchPrefix(step) != null)
}
