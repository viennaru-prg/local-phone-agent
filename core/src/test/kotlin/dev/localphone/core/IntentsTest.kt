package dev.localphone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentsTest {
    private fun json(intent: String, target: String = "", app: String = "") =
        "{\"intent\":\"$intent\",\"target\":\"$target\",\"app\":\"$app\"}"

    @Test fun commandsTheRulesKnowNeverReachTheModel() {
        listOf("수요모임으로 안내해줘", "회사까지 얼마나 걸려", "내비게이션 꺼줘", "더 크로스 틀어줘", "음악 멈춰줘",
            "지금 몇 시야", "설정에서 소프트웨어 정보 보여줘", "클립스트림 열어줘", "이름 모임")
            .forEach { assertFalse(Intents.worthAsking(it), it) }
        assertTrue(Intents.worthAsking("수요모임 가는 길 좀 알려줄래"))
        assertTrue(Intents.worthAsking("늙은 사랑 듣고 싶어"))
        assertFalse(Intents.worthAsking("고마워"))
    }

    @Test fun answersBecomeTheSentencesTheRulesHandle() {
        assertEquals("수요모임으로 안내해줘", Intents.rewrite("수요모임 가는 길 좀 알려줄래", json("navigate", "수요모임")))
        assertEquals("회사까지 얼마나 걸려", Intents.rewrite("회사 가려면 몇 분이나 걸려", json("eta", "회사")))
        assertEquals("내비게이션 꺼줘", Intents.rewrite("길 안내 이제 그만해", json("end_navigation")))
        assertEquals("더 크로스 틀어줘", Intents.rewrite("더 크로스 노래 듣고 싶어", json("play_song", "더 크로스")))
        assertEquals("늙은 사랑 틀어줘", Intents.rewrite("늙은 사랑 듣고 싶어", json("play_song", "늙은 사랑")))
        assertEquals("음악 멈춰줘", Intents.rewrite("노래 잠깐 멈춰봐", json("media", "pause")))
        assertEquals("다음 곡 틀어줘", Intents.rewrite("이 노래 말고 다음 거", json("media", "next")))
        assertEquals("지금 나오는 노래 뭐야", Intents.rewrite("이거 무슨 노래야", json("now_playing")))
        assertEquals("설정에서 와이파이 화면 열어줘", Intents.rewrite("설정 들어가서 와이파이 좀 보자", json("open_screen", "와이파이", "설정")))
        assertEquals("네이버 지도에서 카페 검색해줘", Intents.rewrite("네이버 지도에서 카페 좀 찾아봐", json("search", "카페", "네이버 지도")))
    }

    @Test fun namesMustHaveBeenSaid() {
        // The model may only pick words out of the command, never supply a place of its own.
        assertNull(Intents.rewrite("수요모임 가는 길 좀 알려줄래", json("navigate", "수원집")))
        assertNull(Intents.rewrite("노래 잠깐 멈춰봐", json("media", "louder")))
        assertNull(Intents.rewrite("카카오톡에서 엄마한테 온 메시지 읽어줘", json("none")))
        assertNull(Intents.rewrite("아무 말", "not json"))
    }
}
