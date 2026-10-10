package dev.localphone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationMemoryTest {
    private var clock = 0L
    private val memory = ConversationMemory({ clock })
    private fun done(say: String) = AgentResult(Outcome.DONE, say, emptyList())
    private fun rewritten(goal: String) = (memory.resolve(goal) as? ConversationMemory.Resolution.Rewritten)?.sentence

    @Test fun aPlaceJustAnsweredIsThere() {
        memory.observe("이담한정식까지 얼마나 걸려", done("이담한정식까지 차로 약 15분, 6.4km 걸려요."))
        assertEquals("이담한정식으로 안내해줘", rewritten("거기로 안내해줘"))
        assertEquals("이담한정식까지 얼마나 걸려", rewritten("거기까지 얼마나 걸려"))
        memory.observe("수유 모임으로 안내해줘", done("수요모임으로 안내를 시작했어요."))
        assertEquals("수요모임까지 얼마나 걸려", rewritten("아까 거기까지 얼마나 걸려"))
    }

    @Test fun aSongJustAnsweredIsThatSong() {
        memory.observe("지금 나오는 노래 뭐야", done("지금 '더 크로스' 나오고 있어요."))
        assertEquals("더 크로스 재생목록에 추가해줘", rewritten("그 노래 재생목록에 추가해줘"))
        // A question about the player stays as it is.
        assertEquals(ConversationMemory.Resolution.Same, memory.resolve("이 노래 제목이 뭐야"))
    }

    private fun row(h: Int, m: Int, days: List<Int>) = AlarmRow(0, null, h, m, days, true)

    @Test fun alarmsByOrderDayOrTheOneJustNamed() {
        memory.rememberAlarm("다음 알람")
        assertEquals("다음 알람 꺼줘", rewritten("그 알람 꺼줘"))
        val list = listOf(row(6, 30, (2..6).toList()), row(6, 55, (2..6).toList()), row(7, 30, listOf(7)), row(7, 50, listOf(1)), row(14, 20, listOf(1)))
        memory.rememberAlarmList(AlarmCommands.spokenOrder(list))
        assertEquals("오전 6시 55분 알람 꺼줘", rewritten("두 번째 거 꺼줘"))
        assertEquals("오후 2시 20분 알람 지워줘", rewritten("마지막 알람 지워줘"))
        assertEquals("오후 2시 20분 알람 꺼줘", rewritten("일요일 오후 거 꺼줘"))
        // Two fit "평일": asked, never guessed.
        assertTrue(memory.resolve("평일 거 지워줘") is ConversationMemory.Resolution.Unclear)
    }

    @Test fun thatMeansTheLastThingAndForgetsAfterAWhile() {
        memory.observe("지금 나오는 노래 뭐야", done("지금 '더 크로스' 나오고 있어요."))
        clock += 1000
        memory.observe("회사까지 얼마나 걸려", done("회사까지 차로 약 15분, 5.1km 걸려요."))
        assertEquals("회사로 안내해줘", rewritten("그거로 안내해줘").let { it ?: rewritten("거기로 안내해줘") })
        clock += 10 * 60_000L
        assertTrue(memory.resolve("거기로 안내해줘") is ConversationMemory.Resolution.Unclear)
        // Ordinary commands are untouched.
        assertEquals(ConversationMemory.Resolution.Same, memory.resolve("수요모임으로 안내해줘"))
        assertEquals(ConversationMemory.Resolution.Same, memory.resolve("7시 알람 꺼줘"))
    }
}
