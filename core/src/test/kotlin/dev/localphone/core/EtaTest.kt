package dev.localphone.core

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EtaTest {
    @BeforeTest fun forget() = AppProfiles.restore(emptyMap())

    private fun v(vararg labels: String): ScreenView {
        val nodes = listOf(RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000))) + labels.mapIndexed { i, label ->
            RawNode("r.$i", 0, text = label, clickable = true, bounds = Bounds(0, 100 + i * 100, 1000, 190 + i * 100))
        }
        return ScreenCompactor.compact(Snapshot(NavigationSession.NAVER_MAP, "네이버지도", nodes, 1000, 2000))
    }

    private fun picked(): NavigationSession {
        val s = NavigationSession.forEta("회사까지 얼마나 걸려")!!
        val carousel = v("내위치: 경기 수원시", "닫기", "도착지 입력", "집", "회사")
        s.observe(carousel)
        s.recordAction(carousel, AgentAction.Click(5), true, 0)
        return s
    }

    @Test fun anAdOnTheMapIsNotTheTravelTime() {
        val s = picked()
        // The route has not loaded; only a TV ad mentions minutes.
        val loading = v("경기 수원시 팔달구 고화로39번길 40", "경기 수원시 권선구 고색동 1153", "자동차 탭", "layout no result",
            "닥터X 수술방의 미친계 김지원 금토 밤 9시 50분 SBS", "나중에 출발", "안내시작")
        s.observe(loading)
        assertNull(s.etaAnswer(loading))
    }

    @Test fun theRouteResultGivesTimeAndDistance() {
        val s = picked()
        val loaded = v("경기 수원시 팔달구 고화로39번길 40", "경기 수원시 권선구 고색동 1153", "자동차 16분 탭 16분",
            "닥터X 금토 밤 9시 50분 SBS", "실시간 추천 16분 7.3km 택시비 9,200원", "나중에 출발", "안내시작")
        s.observe(loaded)
        assertEquals("회사까지 차로 약 16분, 7.3km 걸려요.", s.etaAnswer(loaded))
        // A tab time alone is enough when the list is not readable.
        val tabOnly = v("경기 수원시 권선구 고색동 1153", "자동차 1시간 6분 탭", "나중에 출발", "안내시작")
        s.observe(tabOnly)
        assertEquals("회사까지 차로 약 1시간 6분 걸려요.", s.etaAnswer(tabOnly))
    }
}
