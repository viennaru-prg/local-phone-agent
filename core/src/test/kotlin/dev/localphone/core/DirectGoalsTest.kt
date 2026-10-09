package dev.localphone.core

import kotlin.test.*

class DirectGoalsTest {
    @Test fun settingsCategoryRowIsNotProofOfOpeningTheRequestedScreen() {
        val nodes = listOf(RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000)),
            RawNode("r.0", 0, text = "연결", bounds = Bounds(0, 100, 1000, 200)),
            RawNode("r.1", 0, text = "Wi-Fi", clickable = true, bounds = Bounds(0, 400, 1000, 500)))
        val snapshot = Snapshot("com.android.settings", "설정", nodes, 1000, 2000)
        assertFalse(DirectGoals.screenConfirmed(DirectGoals.SettingsScreen.WIFI, ScreenCompactor.compact(snapshot)))
        val opened = snapshot.copy(nodes = listOf(nodes[0], nodes[1].copy(text = "Wi-Fi")))
        assertTrue(DirectGoals.screenConfirmed(DirectGoals.SettingsScreen.WIFI, ScreenCompactor.compact(opened)))
    }
    @Test fun mapAndOtherAppsOpenWithoutPreconfiguredPlaces() {
        assertEquals("지도", DirectGoals.appName("지도 켜줘"))
        assertEquals("네이버 지도", DirectGoals.appName("네이버 지도 앱 열어줘"))
        assertEquals("계산기", DirectGoals.appName("계산기 열어줘"))
    }
    @Test fun singleSettingsScreenUsesAndroidButToggleAndCompoundGoalsDoNot() {
        assertEquals(DirectGoals.SettingsScreen.WIFI, DirectGoals.settingsScreen("설정에서 와이파이 화면 열어줘"))
        assertEquals(DirectGoals.SettingsScreen.BLUETOOTH, DirectGoals.settingsScreen("블루투스 설정 보여줘"))
        assertNull(DirectGoals.settingsScreen("와이파이 꺼줘"))
        assertNull(DirectGoals.settingsScreen("설정에서 와이파이 화면 열고 꺼줘"))
        assertNull(DirectGoals.appName("시계 앱에서 타이머 화면 열어줘"))
        assertNull(DirectGoals.appName("지도 켜고 회사로 안내해줘"))
    }
}
