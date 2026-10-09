package dev.localphone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenTest {
    @Test fun busyMapKeepsItsBottomStartControlAndEnabledState() {
        val roadNodes = (1..100).map { i -> RawNode("r.$i", 0, text = "도로 $i", clickable = true, bounds = Bounds(0, i * 10, 1000, i * 10 + 8)) }
        val start = RawNode("r.101", 0, text = "안내 시작", clickable = true, className = "Button", bounds = Bounds(0, 1800, 1000, 1900))
        val root = RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000))
        val snap = Snapshot(NavigationSession.NAVER_MAP, "네이버 지도", listOf(root) + roadNodes + start, 1000, 2000)
        val ready = ScreenCompactor.compact(snap)
        assertEquals(ScreenCompactor.MAX_ELEMENTS, ready.elements.size)
        assertTrue(ready.elements.any { it.label == "안내 시작" })
        val disabled = ScreenCompactor.compact(snap.copy(nodes = listOf(root) + roadNodes + start.copy(enabled = false)))
        assertTrue(ready.signature != disabled.signature)
    }
    private fun b(t: Int, h: Int = 100) = Bounds(0, t, 1000, t + h)

    @Test fun clickableRowAbsorbsChildText() {
        val snap = Snapshot("com.nhn.android.nmap", "네이버 지도", listOf(
            RawNode("r", -1, className = "FrameLayout", bounds = Bounds(0, 0, 1000, 2000)),
            RawNode("r.0", 0, className = "View", clickable = true, bounds = b(300)),
            RawNode("r.0.0", 1, text = "회사", bounds = b(300, 50)),
            RawNode("r.0.1", 1, text = "서울 강남구 테헤란로 1", bounds = b(350, 50)),
            RawNode("r.1", 0, text = "즐겨찾기", bounds = b(100)),
        ), 1000, 2000)
        val view = ScreenCompactor.compact(snap)
        assertEquals(listOf("즐겨찾기", "회사 서울 강남구 테헤란로 1"), view.elements.map { it.label })
        assertEquals(Kind.TEXT, view.elements[0].kind)
        assertEquals(Kind.ITEM, view.elements[1].kind)
    }

    @Test fun inputShowsHintAndValue() {
        val snap = Snapshot("p", "앱", listOf(
            RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000)),
            RawNode("r.0", 0, text = "회사", hint = "장소 검색", editable = true, className = "EditText", bounds = b(100)),
        ), 1000, 2000)
        val text = ScreenCompactor.compact(snap).render()
        assertTrue("[1] 입력칸 \"장소 검색\" 값=\"회사\"" in text, text)
    }

    @Test fun unlabeledIconUsesViewId() {
        val snap = Snapshot("p", "앱", listOf(
            RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000)),
            RawNode("r.0", 0, viewId = "p:id/btn_search", className = "ImageButton", clickable = true, bounds = b(10, 60)),
        ), 1000, 2000)
        val e = ScreenCompactor.compact(snap).elements.single()
        assertEquals("검색 (btn search)", e.label); assertEquals(Kind.BUTTON, e.kind); assertEquals("상단", e.zone)
    }

    @Test fun grammarListsOnlyRealIds() {
        val snap = Snapshot("p", "앱", listOf(
            RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000)),
            RawNode("r.0", 0, text = "재생", clickable = true, className = "Button", bounds = b(500)),
        ), 1000, 2000)
        val g = ActionGrammar.forView(ScreenCompactor.compact(snap))
        assertTrue("id ::= \"1\"" in g, g)
        assertTrue("type ::=" !in g)
    }

    @Test fun parserRejectsUnknownId() {
        val snap = Snapshot("p", "앱", listOf(RawNode("r", -1, text = "a", bounds = b(0))), 1000, 2000)
        val view = ScreenCompactor.compact(snap)
        assertEquals(AgentAction.Click(1), ActionParser.parse("""{"note":"x","action":"click","id":1}""", view).action)
        runCatching { ActionParser.parse("""{"note":"x","action":"click","id":9}""", view) }
            .onSuccess { error("should fail") }
    }

    @Test fun goalKeyIgnoresSpacingAndPoliteEndings() {
        assertEquals(GoalKey.of("회사로 안내해줘"), GoalKey.of("회사로 안내 해 줘."))
        assertEquals(GoalKey.of("음악 재생"), GoalKey.of("음악 재생해"))
    }
}
