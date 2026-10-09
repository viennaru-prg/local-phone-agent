package dev.localphone.core

import dev.localphone.core.Harness.Auto

/**
 * Generic UI skills, written once for every app. App differences come from [AppProfile]; when a
 * profile does not say, a skill tries the common way first, escalates, and the agent learns which
 * way worked (see [Skills.learnFrom]).
 */
object Skills {
    private val deleteLabel = Regex("^(?:삭제|제거|지우기|빼기|remove|delete)(?:$|\\s·)", RegexOption.IGNORE_CASE)
    private val menuLabel = Regex("메뉴|옵션|더\\s*보기|drawer|more\\s*options|overflow", RegexOption.IGNORE_CASE)

    /** A list row worth acting on: a wide control that names something (not a "+ · row" helper button). */
    fun isRow(view: ScreenView, e: Element) = (e.kind == Kind.BUTTON || e.kind == Kind.ITEM) && e.enabled &&
        !e.label.contains(" · ") && e.bounds.width * 2 >= view.snapshot.width && e.bounds.height >= 80

    private fun area(e: Element) = e.bounds.width.toLong() * e.bounds.height

    /** The innermost list around [row]: the one that scrolls it (the page itself often scrolls too). */
    fun listOf(view: ScreenView, row: Element): Element? = view.lists.filter { l ->
        row.bounds.centerY in l.bounds.top..l.bounds.bottom && row.bounds.centerX in l.bounds.left..l.bounds.right
    }.minByOrNull(::area)

    sealed interface Found {
        data class Row(val row: Element) : Found
        data class Move(val auto: Auto) : Found
        data object Missing : Found
    }

    /**
     * Finds the row naming [query]: a half-hidden row is scrolled into view; a row not on screen is
     * searched for down the list, then up (at most [maxScrolls] since the action that started looking).
     */
    fun findRow(query: String, view: ScreenView, history: List<HistoryLine>, sinceIndex: Int = 0, maxScrolls: Int = 12): Found {
        val rows = view.elements.filter { isRow(view, it) }
        val scrolls = history.drop(sinceIndex).filter { it.action.startsWith("scroll") }
        rows.filter { GoalText.rowMatches(query, it.label) }.maxByOrNull { GoalText.rowScore(query, it.label) }?.let { row ->
            val list = listOf(view, row)
            if (list != null && (row.bounds.top < list.bounds.top || row.bounds.bottom > list.bounds.bottom) && scrolls.size < maxScrolls)
                return Found.Move(Auto(AgentAction.Scroll(if (row.bounds.centerY > list.bounds.centerY) ScrollDir.DOWN else ScrollDir.UP, list.id),
                    "'${row.label.take(20)}' 줄이 잘려 보여 목록을 움직임"))
            return Found.Row(row)
        }
        val list = rows.mapNotNull { listOf(view, it) }.minByOrNull(::area) ?: return Found.Missing
        val downDone = scrolls.any { it.action.startsWith("scroll down") && it.outcome.startsWith("변화 없음") }
        val upDone = scrolls.any { it.action.startsWith("scroll up") && it.outcome.startsWith("변화 없음") }
        if (scrolls.size >= maxScrolls || (downDone && upDone)) return Found.Missing
        return Found.Move(Auto(AgentAction.Scroll(if (downDone) ScrollDir.UP else ScrollDir.DOWN, list.id), "목록에서 '$query' 찾기"))
    }

    /**
     * Plays [row]. With a known way (profile or learned) it is used directly; otherwise a plain tap
     * first, and two taps when the tap only selected the row (ClipStream).
     */
    fun playRow(profile: AppProfile, row: Element, history: List<HistoryLine>): Auto? {
        val tapped = history.count { it.action == "click \"${row.label}\"" && !it.outcome.startsWith("실행 실패") }
        val doubled = history.count { it.action == "double_tap \"${row.label}\"" }
        return when {
            profile.rowPlay == "double_tap" || (profile.rowPlay == null && tapped > 0) ->
                if (doubled >= 2) null else Auto(AgentAction.DoubleTap(row.id), "'${row.label.take(30)}' 줄을 두 번 눌러 재생")
            tapped >= 2 -> null
            else -> Auto(AgentAction.Click(row.id), "'${row.label.take(30)}' 줄을 눌러 재생")
        }
    }

    /**
     * Deletes [row]: a delete control on the row itself when there is one ("삭제 · <row>"); otherwise
     * the icon drawn at the row's end (no node of its own). Done only when the row is gone.
     */
    fun deleteRow(profile: AppProfile, view: ScreenView, row: Element, history: List<HistoryLine>): Auto? {
        if (profile.rowDelete != "trailing_icon") view.elements.firstOrNull { e ->
            e.enabled && e.kind != Kind.TEXT && deleteLabel.containsMatchIn(e.label.trim()) &&
                e.bounds.centerY in row.bounds.top..row.bounds.bottom && e.id != row.id
        }?.let { control ->
            if (history.count { it.action == "click \"${control.label}\"" } < 2)
                return Auto(AgentAction.Click(control.id), "'${row.label.take(30)}' 줄의 '${control.label.substringBefore(" · ")}' 버튼")
        }
        if (history.count { it.action == "click_end \"${row.label}\"" } >= 2) return null
        return Auto(AgentAction.TapEnd(row.id), "'${row.label.take(30)}' 줄 끝의 삭제 아이콘")
    }

    /** The row a delete action was performed on (either way), if the screen changed. */
    fun deletedRow(query: String, history: List<HistoryLine>): String? = history.asReversed().firstNotNullOfOrNull { h ->
        if (!h.outcome.contains("바뀜")) return@firstNotNullOfOrNull null
        val label = Regex("^click(?:_end)? \"(.*)\"$").matchEntire(h.action)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
        val row = when {
            h.action.startsWith("click_end") -> label
            deleteLabel.containsMatchIn(label) -> label.substringAfter(" · ", "")
            else -> ""
        }
        row.takeIf { it.isNotEmpty() && GoalText.rowMatches(query, it) }
    }

    /**
     * Presses the control matching [target]; when it is not on screen, opens a menu-like control
     * ("메뉴·옵션 열기", "더보기") to reveal it. NAVER keeps 안내 종료 in its driving drawer.
     */
    fun pressBehindMenu(target: Regex, view: ScreenView, history: List<HistoryLine>, why: String): Auto? {
        view.elements.firstOrNull { it.enabled && it.kind != Kind.TEXT && target.matches(it.label.trim()) }?.let {
            return Auto(AgentAction.Click(it.id), why)
        }
        val recentMenus = history.takeLast(2).count { h -> menuLabel.containsMatchIn(h.action) }
        if (recentMenus >= 2) return null
        return view.elements.firstOrNull { it.enabled && it.kind != Kind.TEXT && menuLabel.containsMatchIn(it.label) }
            ?.let { Auto(AgentAction.Click(it.id), "'${target.pattern.take(20)}'가 들어 있을 메뉴 열기") }
    }

    /** Records how this app did it, so the next command goes straight to the working way. */
    fun learnFrom(packageName: String, history: List<HistoryLine>, query: String) {
        history.lastOrNull { (it.action.startsWith("double_tap") || it.action.startsWith("click \"")) && GoalText.rowMatches(query, it.action) }?.let {
            AppProfiles.learn(packageName, "rowPlay", if (it.action.startsWith("double_tap")) "double_tap" else "tap")
        }
    }

    fun learnDelete(packageName: String, history: List<HistoryLine>) {
        history.lastOrNull { it.outcome.contains("바뀜") && (it.action.startsWith("click_end") || deleteLabel.containsMatchIn(it.action.removePrefix("click \""))) }?.let {
            AppProfiles.learn(packageName, "rowDelete", if (it.action.startsWith("click_end")) "trailing_icon" else "button")
        }
    }
}
