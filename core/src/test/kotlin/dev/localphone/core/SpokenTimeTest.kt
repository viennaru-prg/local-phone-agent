package dev.localphone.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SpokenTimeTest {
    @Test fun spokenKoreanDurationsArePassedWithExactValues() = runTest {
        for ((command, seconds) in listOf(
            "오 분 타이머 시작 해 줘" to 300,
            "다섯 분 타이머 맞춰 줘" to 300,
            "한 시간 이십 분 삼십 초 타이머 시작해줘" to 4830,
            "5 분 타이머 시작해 줘" to 300,
        )) assertEquals(listOf(Action.SetTimer(seconds)), BasicCommandPlanner().plan(command).actions)
    }
    @Test fun spokenAlarmTimeKeepsItsExplicitPeriodAndRange() = runTest {
        assertEquals(listOf(Action.SetAlarm(7, 30)), BasicCommandPlanner().plan("오전 일곱 시 삼십 분 알람 맞춰 줘").actions)
        assertEquals(listOf(Action.SetAlarm(16, 0)), BasicCommandPlanner().plan("오후 네 시 알람 설정 해 줘").actions)
        for (command in listOf("일곱 시 알람 맞춰줘", "내일 오전 일곱 시 알람 맞춰줘", "영 초 타이머 시작해줘",
            "오전 일곱 시 칠십 분 알람 맞춰줘")) assertNotNull(BasicCommandPlanner().plan(command).unsupportedReason)
    }
    @Test fun appNamesAndPlaceWordsAreNeverConvertedToNumbers() {
        for (command in listOf("오 앱 열어줘", "삼성으로 가자", "내일 시계 앱 열어줘", "사 분 카페 가자"))
            assertEquals(command, SpokenTime.normalize(command))
    }
}
