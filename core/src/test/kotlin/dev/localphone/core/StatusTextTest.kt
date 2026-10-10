package dev.localphone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusTextTest {
    @Test fun stepsReadAsPlainWords() {
        assertEquals("‘길찾기’ 누르는 중…", StatusText.forStep("click [3] \"길찾기\""))
        assertEquals("‘IU (아이유) _ Good D…’ 누르는 중…", StatusText.forStep("double_tap [34] \"IU (아이유) _ Good Day (좋은 날) _ MV\""))
        assertEquals("목록 넘기는 중…", StatusText.forStep("scroll right [14] \"v frequents recycler view\""))
        assertEquals("목록 넘기는 중…", StatusText.forStep("scroll down"))
        assertEquals("클립스트림 여는 중…", StatusText.forStep("open_app \"클립스트림\""))
        assertEquals("‘좋은 날’ 입력하는 중…", StatusText.forStep("type [2] \"검색\" \"좋은 날\" +enter"))
        assertEquals("화면 기다리는 중…", StatusText.forStep("wait"))
        assertEquals("음악 조작 중…", StatusText.forStep("media pause"))
    }

    @Test fun bookkeepingKeepsTheCurrentLine() {
        assertNull(StatusText.forStep("Screen"))
        assertNull(StatusText.forStep("stop"))
        assertNull(StatusText.forStep("ask"))
    }
}
