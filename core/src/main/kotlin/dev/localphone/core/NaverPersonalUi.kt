package dev.localphone.core

/** Personal destinations live in several provider areas, not just the saved-place list. */
object NaverPersonalUi {
    data class Area(val id: String, val labels: List<String>)
    val homeWork = Area("home_work", listOf("집/회사", "집·회사", "집과 회사", "Home/Work", "Home and Work"))
    val frequent = Area("frequent", listOf("자주 가는 곳", "자주가는 장소", "자주 찾는 곳", "Frequent places"))
    private val places = Area("places", listOf("장소", "내 장소", "저장 장소", "저장한 장소", "Places"))
    private val entries = listOf("즐겨찾기", "MY", "마이", "저장", "Saved", "Favorites")

    fun areas(slot: String?) = if (slot == "home" || slot == "office") listOf(homeWork, frequent, places)
        else listOf(frequent, homeWork, places)

    fun targets(screen: UiScreen, labels: List<String>): List<UiNode> {
        val wanted = labels.map(PlaceText::normalize)
        return screen.nodes.filter { !it.editable && (PlaceText.normalize(it.label) in wanted ||
            it.label.split(" · ").any { part -> PlaceText.normalize(part) in wanted }) }
            .map(screen::clickTarget).filter { it.clickable }.distinctBy { it.token }
    }

    fun entries(screen: UiScreen) = entries.flatMap { label -> targets(screen, listOf(label)) }
        .distinctBy { it.token }

    fun rows(screen: UiScreen, aliases: List<String>): List<UiNode> {
        val wanted = aliases.flatMap { PlaceText.variants(it) }.toSet()
        return screen.nodes.filter { node -> !node.editable && node.label.split(" · ").any {
            PlaceText.normalize(it) in wanted
        } }.map(screen::clickTarget).distinctBy { it.token }.filter { row ->
            if (!row.clickable) return@filter false
            val labels = (listOf(row) + screen.descendants(row.token)).flatMap { it.label.split(" · ") }
                .map(PlaceText::normalize)
            labels.none { it in listOf("등록", "추가", "등록하기", "추가하기", "집등록", "회사등록", "집설정", "회사설정") ||
                Regex("^(?:집|회사).*(?:등록하세요|등록해|추가하세요)").containsMatchIn(it) }
        }
    }

    /** A horizontal category strip must not consume the destination list's scroll budget. */
    fun contentScroll(screen: UiScreen): UiNode? = screen.nodes.firstOrNull { node ->
        node.scrollable && !node.role.contains("Horizontal", ignoreCase = true) &&
            (node.role.contains("ScrollView", ignoreCase = true) || listOf(homeWork, frequent).none { area -> targets(screen, area.labels).any { target ->
                target.token == node.token || screen.descendants(node.token).any { it.token == target.token }
            } })
    }
}
