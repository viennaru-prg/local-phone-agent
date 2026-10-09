package dev.localphone.core

/** Only single app/screen-opening goals; compound commands and setting changes stay with the agent. */
object DirectGoals {
    enum class SettingsScreen { WIFI, BLUETOOTH, DISPLAY, SOUND, BATTERY, ALARM }
    fun screenConfirmed(screen: SettingsScreen, view: ScreenView): Boolean {
        val names = when (screen) {
            SettingsScreen.WIFI -> listOf("와이파이", "wi-fi", "wifi")
            SettingsScreen.BLUETOOTH -> listOf("블루투스", "bluetooth")
            SettingsScreen.DISPLAY -> listOf("디스플레이", "display")
            SettingsScreen.SOUND -> listOf("소리및진동", "소리", "sound")
            // Device Care titles the screen POWER_USAGE_SUMMARY opens "배터리 활동" on One UI 7+.
            SettingsScreen.BATTERY -> listOf("배터리", "battery", "배터리활동", "배터리사용량", "batteryusage")
            SettingsScreen.ALARM -> listOf("알람", "alarm", "alarms")
        }
        // The clock app shows its alarm list as the selected "알람" tab, not as a title.
        // Samsung Clock opened by SHOW_ALARMS shows neither: its alarm list and "알람 추가" are the proof.
        if (screen == SettingsScreen.ALARM && view.elements.any {
                (it.selected && GoalText.normalize(it.label) in names) || GoalText.normalize(it.label) in listOf("알람추가", "addalarm", "alarmmainlayout")
            }) return true
        return view.elements.any { e ->
            names.any { GoalText.normalize(e.label) == it } &&
                ((e.kind == Kind.TEXT && e.bounds.top < view.snapshot.height * 0.3) ||
                    Regex("collapsing_app_bar_title|toolbar_title|action_bar_title").containsMatchIn(view.snapshot.nodes[e.node].viewId))
        }
    }
    fun settingsScreen(goal: String): SettingsScreen? {
        val g = GoalText.normalize(goal)
        val name = Regex("^(?:설정(?:앱)?(?:에서|의)?)?(와이파이|wi-fi|wifi|블루투스|디스플레이|소리및진동|소리|배터리|알람)(?:설정)?(?:화면|메뉴|페이지)?(?:열어|보여)(?:줘|주세요)?$")
            .matchEntire(g)?.groupValues?.get(1) ?: return null
        return when (name) {
            "와이파이", "wi-fi", "wifi" -> SettingsScreen.WIFI
            "블루투스" -> SettingsScreen.BLUETOOTH
            "디스플레이" -> SettingsScreen.DISPLAY
            "소리및진동", "소리" -> SettingsScreen.SOUND
            "배터리" -> SettingsScreen.BATTERY
            "알람" -> SettingsScreen.ALARM
            else -> null
        }
    }

    fun appName(goal: String): String? {
        if (Regex("에서|화면|메뉴|페이지|검색|찾아|그리고|하고|열고|켜고").containsMatchIn(goal)) return null
        val name = Regex("^(.+?)(?:\\s*앱)?\\s*(?:켜\\s*줘|열어\\s*줘|실행(?:해)?\\s*줘|열어주세요|켜주세요)\\s*[.!?]?$" )
            .matchEntire(goal.trim())?.groupValues?.get(1)?.trim() ?: return null
        return name.takeIf { it.length in 1..40 }
    }
}
