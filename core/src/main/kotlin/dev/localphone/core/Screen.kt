package dev.localphone.core

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val empty get() = width <= 0 || height <= 0
}

/** One accessibility node as read from the device. `parent` indexes into the same snapshot list. */
data class RawNode(
    val path: String,
    val parent: Int,
    val text: String = "",
    val desc: String = "",
    val hint: String = "",
    val viewId: String = "",
    val className: String = "",
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val bounds: Bounds = Bounds(0, 0, 0, 0),
) {
    val ownLabel: String get() = listOf(text, desc).map(String::trim).filter(String::isNotEmpty).distinct().joinToString(" ")
}

/** [home] = the launcher is showing. Its icon grid is hidden from the model; apps are opened with open_app. */
data class Snapshot(val packageName: String, val appLabel: String, val nodes: List<RawNode>, val width: Int, val height: Int,
                    val home: Boolean = false)

enum class Kind(val word: String) { BUTTON("버튼"), INPUT("입력칸"), SWITCH("스위치"), ITEM("항목"), TEXT("텍스트"), LIST("스크롤목록") }

/** A numbered element shown to the model. `node` is the index of the node that receives the action. */
data class Element(val id: Int, val node: Int, val kind: Kind, val label: String, val value: String = "",
                   val checked: Boolean? = null, val selected: Boolean = false, val enabled: Boolean = true,
                   val bounds: Bounds, val zone: String = "")

data class ScreenView(val snapshot: Snapshot, val elements: List<Element>) {
    fun element(id: Int) = elements.firstOrNull { it.id == id }
    val inputs get() = elements.filter { it.kind == Kind.INPUT }
    val lists get() = elements.filter { it.kind == Kind.LIST }
    /** Stable signature for "did the screen change" checks; ignores ids and bounds. */
    val signature: Int get() = (snapshot.packageName + elements.joinToString("|") { "${it.kind}:${it.label}:${it.value}:${it.checked}:${it.selected}:${it.enabled}" }).hashCode()

    /**
     * [goalWords] mark actionable elements whose label contains a word of the goal with ★.
     * [clicked] labels that were just pressed are marked so the model moves on to the next step.
     */
    fun render(goalWords: List<String> = emptyList(), clicked: Set<String> = emptySet()): String = buildString {
        if (snapshot.home) { append("앱: 홈 화면 (앱 아이콘 목록은 생략. 필요한 앱은 open_app으로 연다)\n"); return@buildString }
        append("앱: ").append(snapshot.appLabel.ifBlank { snapshot.packageName }).append('\n')
        if (elements.isEmpty()) append("(읽을 수 있는 요소 없음)\n")
        for (e in elements) {
            append('[').append(e.id).append("] ").append(e.kind.word)
            if (e.label.isNotEmpty()) append(" \"").append(e.label).append('"')
            if (e.kind == Kind.INPUT) append(" 값=\"").append(e.value).append('"')
            e.checked?.let { append(if (it) " (켜짐)" else " (꺼짐)") }
            if (e.selected) append(" (선택됨)")
            if (!e.enabled) append(" (비활성)")
            if (e.zone.isNotEmpty()) append(" @").append(e.zone)
            val justClicked = e.label in clicked
            if (justClicked) append(" (방금 누름)")
            else if (e.kind != Kind.TEXT && goalWords.isNotEmpty() && GoalText.matches(e.label, goalWords)) append(" ★")
            append('\n')
        }
    }
}

/**
 * Turns a raw accessibility tree into a short numbered list. Clickable containers absorb the text of
 * their non-clickable descendants, so a list row reads as one item with its real content instead of
 * an empty "View".
 */
object ScreenCompactor {
    const val MAX_ELEMENTS = 70
    const val MAX_LABEL = 60

    fun compact(snapshot: Snapshot): ScreenView {
        if (snapshot.home) return ScreenView(snapshot, emptyList())
        val nodes = snapshot.nodes
        val children = Array(nodes.size) { mutableListOf<Int>() }
        nodes.forEachIndexed { index, node -> if (node.parent in nodes.indices) children[node.parent] += index }
        fun absorbs(n: RawNode) = n.clickable || n.longClickable || n.checkable || n.editable
        fun visible(n: RawNode) = !n.bounds.empty && n.bounds.right > 0 && n.bounds.bottom > 0 &&
            n.bounds.left < snapshot.width && n.bounds.top < snapshot.height

        // Nearest absorbing ancestor for each node.
        val owner = IntArray(nodes.size) { -1 }
        nodes.forEachIndexed { index, node ->
            var p = node.parent
            while (p in nodes.indices) { if (absorbs(nodes[p])) { owner[index] = p; break }; p = nodes[p].parent }
        }
        fun mergedText(root: Int): String {
            val parts = mutableListOf<String>()
            fun walk(i: Int) {
                for (c in children[i]) {
                    val child = nodes[c]
                    if (absorbs(child)) continue // gets its own element
                    if (child.ownLabel.isNotEmpty()) parts += child.ownLabel
                    walk(c)
                }
            }
            walk(root)
            return parts.distinct().joinToString(" ")
        }

        data class Draft(val node: Int, val kind: Kind, val label: String, val value: String, val checked: Boolean?)
        val drafts = mutableListOf<Draft>()
        nodes.forEachIndexed { index, node ->
            if (!visible(node)) return@forEachIndexed
            when {
                node.editable -> drafts += Draft(index, Kind.INPUT, node.hint.ifBlank { node.desc }.ifBlank { idWord(node.viewId) },
                    if (node.text == node.hint) "" else node.text, null)
                node.checkable -> drafts += Draft(index, Kind.SWITCH, join(node.ownLabel, mergedText(index)).ifBlank { idWord(node.viewId) }, "", node.checked)
                node.clickable || node.longClickable -> {
                    val label = join(node.ownLabel, mergedText(index)).ifBlank { idWord(node.viewId) }
                    if (label.isNotEmpty()) drafts += Draft(index, if (isButton(node)) Kind.BUTTON else Kind.ITEM, label, "", null)
                }
                node.scrollable -> drafts += Draft(index, Kind.LIST, idWord(node.viewId), "", null)
                owner[index] == -1 && node.ownLabel.isNotEmpty() -> drafts += Draft(index, Kind.TEXT, node.ownLabel, "", null)
            }
        }
        // Remove exact duplicates (same text drawn twice, e.g. a TextView and its contentDescription parent).
        val unique = drafts.distinctBy { Triple(it.kind, it.label, nodes[it.node].bounds) }
        // A dense map can have scores of road labels above its bottom '안내 시작' control. Preserve
        // meaningful controls before trimming, then restore their screen order for the model.
        fun priority(d: Draft): Int = when {
            Regex("안내\\s*시작|안내\\s*종료|목적지|도착지|^완료$|^확인$").containsMatchIn(d.label) -> 0
            d.kind == Kind.INPUT || d.kind == Kind.SWITCH -> 1
            d.kind == Kind.BUTTON || nodes[d.node].selected -> 2
            d.kind != Kind.TEXT -> 3
            else -> 4
        }
        val sorted = unique.sortedWith(compareBy<Draft>({ priority(it) }, { nodes[it.node].bounds.top / 8 }, { nodes[it.node].bounds.left }))
            .take(MAX_ELEMENTS).sortedWith(compareBy({ nodes[it.node].bounds.top / 8 }, { nodes[it.node].bounds.left }))
        val elements = sorted.mapIndexed { i, d ->
            val b = nodes[d.node].bounds
            Element(i + 1, d.node, d.kind, clip(d.label), clip(d.value), d.checked, nodes[d.node].selected, nodes[d.node].enabled, b,
                zone(b, snapshot.height))
        }
        return ScreenView(snapshot, elements)
    }

    private fun join(a: String, b: String) = listOf(a, b).filter(String::isNotBlank).distinct().joinToString(" ").trim()
    private fun clip(s: String) = s.replace(Regex("\\s+"), " ").trim().let { if (it.length > MAX_LABEL) it.take(MAX_LABEL - 1) + "…" else it }
    private fun isButton(n: RawNode) = n.className.endsWith("Button") || n.className.endsWith("ImageView")
    /** "com.nhn.android.nmap:id/btn_search" -> "btn search"; better than nothing for unlabeled icons. */
    fun idWord(viewId: String): String {
        val raw = viewId.substringAfterLast('/').replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').trim()
        val words = raw.lowercase().split(' ')
        // Hints from the live resource name, for unlabeled icons in any app. They describe an
        // affordance only; the model still chooses the next action for the full user goal.
        val hint = when {
            words.any { it in setOf("drawer", "menu", "overflow", "more", "options") } -> "메뉴·옵션 열기"
            "reroute" in words -> "경로 다시 계산"
            words.any { it in setOf("refresh", "reload") } -> "새로고침"
            "close" in words -> "닫기"
            "cancel" in words -> "취소"
            "back" in words -> "뒤로"
            "search" in words -> "검색"
            else -> ""
        }
        return if (hint.isBlank()) raw else "$hint ($raw)"
    }
    private fun zone(b: Bounds, height: Int): String = when {
        height <= 0 -> ""
        b.bottom <= height * 0.12 -> "상단"
        b.top >= height * 0.86 -> "하단"
        else -> ""
    }
}
