package dev.localphone.core

import kotlin.test.*

class NaverPersonalUiTest {
    private fun screen(vararg nodes: UiNode) = UiScreen(NaverLinks.PACKAGE, 1, nodes.toList())
    @Test fun registeredRolesTakePriorityOverGenericFavoriteNicknames() {
        assertEquals("home_work", NaverPersonalUi.areas("office").first().id)
        assertEquals("home_work", NaverPersonalUi.areas("home").first().id)
        assertEquals("frequent", NaverPersonalUi.areas(null).first().id)
    }
    @Test fun combinedHomeWorkLabelUsesClickableComposeParent() {
        val ui = screen(UiNode("tab", "", clickable = true), UiNode("text", "집/회사", parent = "tab"),
            UiNode("button", "", role = "Button", parent = "tab"))
        assertEquals("tab", NaverPersonalUi.targets(ui, NaverPersonalUi.homeWork.labels).single().token)
    }
    @Test fun aFavoritesHeadingCannotBecomeAnEntryPoint() {
        val ui = screen(UiNode("root", ""), UiNode("header", "즐겨찾기", parent = "root"),
            UiNode("my", "MY", clickable = true))
        assertEquals(listOf("my"), NaverPersonalUi.entries(ui).map { it.token })
    }
    @Test fun unregisteredRoleDoesNotOpenAnAddressEditor() {
        val ui = screen(UiNode("row", "", clickable = true), UiNode("label", "회사", parent = "row"),
            UiNode("empty", "등록", parent = "row"))
        assertTrue(NaverPersonalUi.rows(ui, listOf("회사")).isEmpty())
    }
    @Test fun duplicateFrequentNicknamesRemainDifferentDestinationChoices() {
        val ui = screen(UiNode("a", "", clickable = true), UiNode("a.name", "본가", parent = "a"),
            UiNode("a.address", "서울시 합성로 11", parent = "a"), UiNode("b", "", clickable = true),
            UiNode("b.name", "본가", parent = "b"), UiNode("b.address", "서울시 합성로 22", parent = "b"))
        assertEquals(listOf("a", "b"), NaverPersonalUi.rows(ui, listOf("본가")).map { it.token })
    }
    @Test fun categoryStripIsExcludedFromThePlaceListsScrollTarget() {
        val ui = screen(UiNode("strip", "", scrollable = true), UiNode("tab", "자주 가는 곳", clickable = true, parent = "strip"),
            UiNode("list", "", scrollable = true), UiNode("row", "테스트치과", clickable = true, parent = "list"))
        assertEquals("list", NaverPersonalUi.contentScroll(ui)?.token)
    }
    @Test fun splitDescriptionsAndSpacingStillResolveFrequentArea() {
        val ui = screen(UiNode("tab", "자주 가는 곳 · 탭", clickable = true))
        assertEquals("tab", NaverPersonalUi.targets(ui, NaverPersonalUi.frequent.labels).single().token)
    }
    @Test fun aRegisteredComposeRowIsSelectedRatherThanItsEditButton() {
        val ui = screen(UiNode("row", "", clickable = true), UiNode("name", "회사", parent = "row"),
            UiNode("address", "서울시 합성로 11", parent = "row"), UiNode("edit", "수정", clickable = true, parent = "row"))
        assertEquals("row", NaverPersonalUi.rows(ui, listOf("직장", "회사")).single().token)
    }
}
