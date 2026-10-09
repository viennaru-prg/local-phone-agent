package dev.localphone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandSplitTest {
    @Test fun twoIndependentCommandsAreSplit() {
        assertEquals(listOf("수원 집으로 안내해 줘", "노래 틀어 줘"), CommandSplit.split("수원 집으로 안내해 주고 노래 틀어 줘"))
        assertEquals(listOf("회사로 안내해줘", "음악 틀어줘"), CommandSplit.split("회사로 안내하고 음악 틀어줘"))
        assertEquals(listOf("음악 멈춰줘", "길안내 종료해줘"), CommandSplit.split("음악 멈추고 길안내 종료해줘"))
        assertEquals(listOf("늙은 사랑 틀어줘", "일요모임으로 안내해줘"), CommandSplit.split("늙은 사랑 틀어줘 그리고 일요모임으로 안내해줘"))
        assertEquals(listOf("클립스트림 열어줘", "더 크로스 틀어줘"), CommandSplit.split("클립스트림 열고 더 크로스 틀어줘"))
        // A song named as an afterthought belongs to the play request.
        assertEquals(listOf("집으로 안내해 줘", "자전거를 탄 풍경 틀어줘"), CommandSplit.split("집으로 안내해 주고 노래 틀어 줘 노래는 자전거를 탄 풍경"))
    }

    @Test fun chainedOrSingleCommandsStayWhole() {
        assertNull(CommandSplit.split("유튜브에서 아이유 검색해서 첫 번째 영상 틀어줘"))
        assertNull(CommandSplit.split("회사로 안내해줘"))
        assertNull(CommandSplit.split("설정에서 배터리 화면 열어줘"))
        // "…하고" inside one request that is not two commands
        assertNull(CommandSplit.split("친구랑 이야기하고 싶어"))
    }
}
