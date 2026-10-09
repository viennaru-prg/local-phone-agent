package dev.localphone.core

/** Conservative shortcut boundary. Complex goals stay intact for the general screen agent. */
object GoalScope {
    fun multiple(goal: String) = Regex("그리고|또한|한\\s*(?:다음|뒤|후)|다음에|하면서|\\S+고\\s|해서\\s|찾아서|검색해서|및|;|\\n").containsMatchIn(goal)
    /** Language-level checklist, never an app-specific procedure or an execution plan. */
    fun parts(goal: String): List<String> = goal.split(Regex("그리고|또한|한\\s*(?:다음|뒤|후)|다음에|하면서|(?:하고|고|해서|찾아서|검색해서)\\s+|및|;|\\n"))
        .map(String::trim).filter(String::isNotEmpty).ifEmpty { listOf(goal) }
}
