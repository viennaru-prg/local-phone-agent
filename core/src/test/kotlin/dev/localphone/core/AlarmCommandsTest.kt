package dev.localphone.core

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlarmCommandsTest {
    @Test fun settingAlarmsOnceWeeklyOrFromNow() {
        assertEquals(QuickRequest.Alarm(7, 0), QuickCommands.parse("7시 알람 설정해줘"))
        assertEquals(QuickRequest.Alarm(7, 0, listOf(2, 4, 6)), QuickCommands.parse("매주 월수금 7시에 알람 넣어줘"))
        assertEquals(QuickRequest.Alarm(6, 30, (2..6).toList()), QuickCommands.parse("평일마다 아침 6시 반에 깨워줘"))
        assertEquals(QuickRequest.Alarm(9, 0, listOf(1, 7)), QuickCommands.parse("주말 9시 알람 맞춰줘"))
        assertEquals(QuickRequest.Alarm(18, 0, listOf(3), "운동"), QuickCommands.parse("매주 화요일 저녁 6시에 '운동' 알람 만들어줘"))
        assertEquals(QuickRequest.AlarmIn(30), QuickCommands.parse("30분 뒤에 알람 맞춰줘"))
        assertEquals(QuickRequest.AlarmIn(90), QuickCommands.parse("1시간 반 후에 깨워줘"))
    }

    @Test fun questionsAndChanges() {
        assertEquals(AlarmRequest.Next, AlarmCommands.parse("다음 알람 언제야"))
        assertEquals(AlarmRequest.Next, AlarmCommands.parse("알람 몇 시에 울려?"))
        assertEquals(AlarmRequest.List, AlarmCommands.parse("알람 일정 알려줘"))
        assertEquals(AlarmRequest.List, AlarmCommands.parse("알람 뭐 맞춰져 있어?"))
        assertEquals(AlarmRequest.Switch(AlarmTarget.Next, on = false, once = true), AlarmCommands.parse("다음 알람 꺼줘"))
        assertEquals(AlarmRequest.Switch(AlarmTarget.At(7, 30), on = false, once = false), AlarmCommands.parse("7시 반 알람 꺼줘"))
        assertEquals(AlarmRequest.Switch(AlarmTarget.At(7, 30), on = false, once = true), AlarmCommands.parse("7시 반 알람 이번만 꺼줘"))
        assertEquals(AlarmRequest.Switch(AlarmTarget.At(14, 20), on = true, once = false), AlarmCommands.parse("오후 2시 20분 알람 다시 켜줘"))
        assertEquals(AlarmRequest.Sound(AlarmTarget.Next, null), AlarmCommands.parse("알람 음악 교체해줘"))
        assertEquals(AlarmRequest.Sound(AlarmTarget.At(6, 30), "Over the Horizon"), AlarmCommands.parse("6시 반 알람 소리 Over the Horizon으로 바꿔줘"))
        // Setting one is the quick command's; other words are not alarms.
        assertNull(AlarmCommands.parse("7시 알람 설정해줘"))
        assertNull(AlarmCommands.parse("음악 꺼줘"))
    }

    private fun list(vararg switches: Pair<String, Boolean>): ScreenView {
        val nodes = listOf(RawNode("r", -1, bounds = Bounds(0, 0, 1080, 2400))) + switches.mapIndexed { i, (label, on) ->
            RawNode("r.$i", 0, desc = label, className = "android.widget.Switch", clickable = true, checkable = true, checked = on,
                bounds = Bounds(900, 400 + i * 200, 1060, 580 + i * 200))
        }
        return ScreenCompactor.compact(Snapshot("com.sec.android.app.clockpackage", "시계", nodes, 1080, 2400))
    }

    @Test fun theListIsReadFromItsSwitches() {
        val v = list("테스트알람, 오전 04:44, 매일" to false, "오전 06:30, 공휴일에는 끄기, 월요일, 화요일, 수요일, 목요일, 금요일" to true,
            "오전 07:30, 토요일" to true, "오후 02:20, 일요일" to true)
        val rows = AlarmCommands.rows(v)
        assertEquals(listOf(4 to 44, 6 to 30, 7 to 30, 14 to 20), rows.map { it.hour to it.minute })
        assertEquals("테스트알람", rows[0].label)
        assertEquals((2..6).toList(), rows[1].days)
        assertEquals("켜진 알람은 3개예요. 평일 오전 6시 30분, 토요일 오전 7시 30분, 일요일 오후 2시 20분. 꺼진 알람이 1개 더 있어요.",
            AlarmCommands.summary(rows))
        val once = AlarmCommands.rows(list("오후 08:52, 10월 10일 토요일" to true)).single()
        assertEquals(emptyList(), once.days)
        assertEquals("10월 10일 토요일 오후 8시 52분", AlarmCommands.describe(once))
        val sunday = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 11, 14, 20, 0) }.timeInMillis
        assertEquals(14 to 20, AlarmCommands.rowAt(rows, sunday)?.let { it.hour to it.minute })
    }

    @Test fun theNextRingIsSaidWithItsDay() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 10, 20, 17, 0) }.timeInMillis
        val at = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 11, 7, 50, 0) }.timeInMillis
        assertEquals("다음 알람은 내일 오전 7시 50분이에요. 11시간 33분 남았어요.", AlarmCommands.next(now, at))
        assertEquals("예정된 알람이 없어요.", AlarmCommands.next(now, null))
    }
}
