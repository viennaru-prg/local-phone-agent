package dev.localphone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RowContextTest {
    /** ClipStream search results: a clickable full-screen panel, each row a title and a "+" button. */
    @Test fun repeatedRowButtonsAreNamedAfterTheirRow() {
        fun row(i: Int, title: String, top: Int) = listOf(
            RawNode("r.0.1.$i", 2, text = title, bounds = Bounds(200, top, 800, top + 60)),
            RawNode("r.0.1.$i.b", 2, desc = "현재 재생목록에 추가", clickable = true, className = "android.widget.Button",
                bounds = Bounds(880, top - 20, 1000, top + 100)))
        val nodes = listOf(
            RawNode("r", -1, bounds = Bounds(0, 0, 1080, 2340)),
            RawNode("r.0", 0, clickable = true, bounds = Bounds(0, 0, 1080, 2000)),
            RawNode("r.0.1", 1, scrollable = true, bounds = Bounds(0, 400, 1080, 1100)),
        ) + row(0, "아이유(IU) - 좋은 날 [가사]", 500) + row(1, "Good day (inst)", 700) +
            // Off the results box: must not be shown (it would pick up the playlist row behind it).
            row(2, "IU 좋은 날 라이브", 1300)
        val view = ScreenCompactor.compact(Snapshot("p", "P", nodes, 1080, 2340))
        val adds = view.elements.filter { it.label.startsWith("현재 재생목록에 추가") }.map { it.label }
        assertEquals(listOf("현재 재생목록에 추가 · 아이유(IU) - 좋은 날 [가사]", "현재 재생목록에 추가 · Good day (inst)"), adds, view.render())
        assertTrue(view.elements.any { it.kind == Kind.TEXT && it.label.startsWith("아이유(IU)") }, view.render())
    }
}
