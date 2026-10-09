package dev.localphone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickCommandsTest {
    @Test fun everydayRequestsAreRecognized() {
        assertEquals(QuickRequest.Time, QuickCommands.parse("지금 몇 시야?"))
        assertEquals(QuickRequest.Date, QuickCommands.parse("오늘 며칠이야"))
        assertEquals(QuickRequest.Battery, QuickCommands.parse("배터리 얼마나 남았어?"))
        assertEquals(QuickRequest.Volume(2), QuickCommands.parse("볼륨 좀 올려줘"))
        assertEquals(QuickRequest.Volume(-2), QuickCommands.parse("볼륨 내려줘"))
        assertEquals(QuickRequest.Volume(-1), QuickCommands.parse("소리 조금 줄여줘"))
        assertEquals(QuickRequest.Volume(0, toMax = true), QuickCommands.parse("볼륨 최대로 해줘"))
        assertEquals(QuickRequest.Timer(60), QuickCommands.parse("타이머 1분 맞춰줘"))
        assertEquals(QuickRequest.Timer(5400), QuickCommands.parse("타이머 한 시간 반 맞춰줘"))
        assertEquals(QuickRequest.Timer(90), QuickCommands.parse("타이머 1분 30초"))
        assertEquals(QuickRequest.Alarm(7, 30), QuickCommands.parse("아침 7시 반에 알람 맞춰줘"))
        assertEquals(QuickRequest.Alarm(18, 0), QuickCommands.parse("오후 6시에 깨워줘"))
        assertEquals("회사", QuickCommands.etaTarget("회사까지 얼마나 걸려?"))
        assertEquals("수원 집", QuickCommands.etaTarget("수원 집까지 몇 분 걸려"))
        assertTrue(QuickCommands.asksNowPlaying("지금 나오는 노래 뭐야?"))
    }

    @Test fun otherCommandsAreLeftAlone() {
        assertNull(QuickCommands.parse("회사로 안내해줘"))
        assertNull(QuickCommands.parse("알람 화면 열어줘"))
        assertNull(QuickCommands.parse("타이머 꺼줘"))
        assertNull(QuickCommands.parse("음악 틀어줘"))
        assertNull(QuickCommands.etaTarget("회사로 안내해줘"))
    }
}
