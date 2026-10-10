package dev.localphone.core

/** What closing an app came to: closed, the navigation app is still guiding, or the name is no app. */
sealed interface CloseResult {
    data class Closed(val say: String) : CloseResult
    /**
     * The navigation app: whether it is guiding shows only once the map has settled (it flashes its home
     * first), so the guidance check runs before it is closed. While guiding, "종료해줘" ends the drive.
     */
    data object NavigationApp : CloseResult
    /** Not an app ("와이파이 꺼줘", "알람 꺼줘"): the command is handled as before. */
    data object NotAnApp : CloseResult
}

/**
 * "종료해줘", "꺼줘", "네이버 지도 종료해줘": close the app in front or the one named. While the map is
 * guiding, a bare "종료해줘" ends the guidance (the drive is what is running); with guidance already
 * over, it closes the map. Guidance words ("안내 종료해줘") and music ("음악 꺼줘") keep their own meaning.
 */
object AppClose {
    private val close = Regex("^\\s*(?:(.+?)\\s*(?:을|를|은|는)?\\s+)?(?:종료|꺼|닫아|끝내)(?:\\s*해)?\\s*(?:줘|주세요|줄래|봐)?\\s*[.!?]?\\s*$")
    private val front = Regex("^(?:앱|이\\s*앱|이거|지금\\s*앱|현재\\s*앱|화면|이\\s*화면)$")

    /** The app to close: "" for the one in front, null when the command is not about closing an app. */
    fun target(goal: String): String? {
        if (Harness.isEndGuidance(goal) || Router.mediaKeyIn(goal) != null || GoalScope.multiple(goal)) return null
        val name = close.matchEntire(goal)?.groupValues?.get(1)?.trim() ?: return null
        val bare = name.removeSuffix("앱").trim()
        return if (name.isEmpty() || front.matches(name)) "" else bare.ifEmpty { "" }
    }
}
