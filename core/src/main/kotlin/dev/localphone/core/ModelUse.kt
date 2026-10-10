package dev.localphone.core

/**
 * Whether a command will likely need the language model. Most commands are finished by Android
 * APIs and the harness alone (navigation, music, quick answers, named screens, searches); warming
 * the model for them only heated the phone. Unknown commands still warm it up front.
 */
object ModelUse {
    fun likely(goal: String): Boolean {
        CommandSplit.split(goal)?.let { steps -> return steps.any(::likelyOne) }
        return likelyOne(goal)
    }

    private fun likelyOne(goal: String): Boolean = !(
        QuickCommands.parse(goal) != null || AppClose.target(goal) != null || QuickCommands.etaTarget(goal) != null || QuickCommands.asksNowPlaying(goal) ||
            Router.mediaKeyIn(goal) != null || Router.isNavigationGoal(goal) || Harness.isEndGuidance(goal) ||
            (GoalText.playSong(goal) != null && GoalText.namedApp(goal).let { it == null || it in musicApps() }) || GoalText.playlistAdd(goal) != null || GoalText.playlistRemove(goal) != null ||
            DirectGoals.settingsScreen(goal) != null || DirectGoals.appName(goal) != null ||
            ShortcutGoals.screenName(goal) != null || ShortcutGoals.searchPrefix(goal) != null
        )

    private fun musicApps() = AppProfiles.forRole("music")?.names.orEmpty()
}
