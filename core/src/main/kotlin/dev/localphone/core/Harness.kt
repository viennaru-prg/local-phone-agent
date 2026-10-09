package dev.localphone.core

/**
 * Deterministic decisions the harness makes without asking the model, because a small model gets
 * them wrong and the right answer does not depend on the app.
 */
object Harness {
    private val resumeQuestion = Regex("이어서|이어보기|이어 듣기|이어듣기|계속\\s*(?:하시|보시|들으시|받으시)|이전.*(?:다시|계속)|resume|continue", RegexOption.IGNORE_CASE)
    private val asking = Regex("\\?|하시겠습니까|할까요|하시겠어요|받으시겠|보시겠|들으시겠")
    private val decline = Regex("^(아니요|아니오|아니|취소|닫기|나중에|새로\\s*시작|처음부터|no|cancel|not now|close)$", RegexOption.IGNORE_CASE)
    private val wantsResume = Regex("이어|계속|resume|continue", RegexOption.IGNORE_CASE)

    data class Auto(val action: AgentAction, val reason: String)

    private val auth = Regex("잠금\\s*해제|패턴을\\s*그리|비밀번호를?\\s*입력|PIN\\s*(?:을|를)?\\s*입력|지문|생체\\s*인증|본인\\s*인증|unlock|enter (?:your )?(?:pin|password)|draw (?:your )?pattern", RegexOption.IGNORE_CASE)

    /** A lock / authentication screen (Secure Folder pattern, app PIN). Only the user may get past it. */
    /** A lock prompt is text on the screen ("패턴을 그리세요"); Settings lists "지문", "잠금 해제" as menu entries. */
    fun authScreen(view: ScreenView): Boolean = !view.snapshot.packageName.startsWith("com.android.settings") &&
        view.elements.any { it.kind == Kind.TEXT && auth.containsMatchIn(it.label) }

    /** Returns an action to take without the model, or null to let the model decide. */
    fun preDecide(goal: String, view: ScreenView, history: List<HistoryLine> = emptyList()): Auto? {
        emptyScreen(view, history)?.let { return it }
        mediaControl(goal, view, history)?.let { return it }
        playlistAdd(goal, view, history)?.let { return it }
        playlistRemove(goal, view, history)?.let { return it }
        playSong(goal, view, history)?.let { return it }
        endGuidance(goal, view, history)?.let { return it }
        if (!ShortcutGoals.allowsUiHeuristics(goal)) return null
        resumePrompt(goal, view)?.let { return it }
        unrelatedSheet(goal, view, history)?.let { return it }
        commitLoading(goal, view, history)?.let { return it }
        commitAfterTarget(goal, view, history)?.let { return it }
        search(goal, view, history)?.let { return it }
        openNamedItem(goal, view, history)?.let { return it }
        reverseScroll(history)?.let { return it }
        return null
    }

    /**
     * A bottom sheet left open (Android sheets expose a "touch outside" area) that has nothing to do
     * with the goal hides the app's real controls: close it with Back, once.
     */
    private fun unrelatedSheet(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        if (view.elements.none { it.label.equals("touch outside", true) }) return null
        val words = GoalText.targetWords(goal)
        if (view.elements.any { it.kind != Kind.TEXT && GoalText.matches(it.label, words) }) return null
        if (history.lastOrNull()?.action == "back") return null
        return Auto(AgentAction.Back, "목표와 관계없는 패널이 열려 있어 닫음")
    }

    private const val EMPTY = "화면 전환 중이라 기다림"

    /** Nothing readable yet (a screen transition): acting now means acting blind, so wait. */
    private fun emptyScreen(view: ScreenView, history: List<HistoryLine>): Auto? {
        if (view.snapshot.home || view.elements.any { it.kind != Kind.LIST }) return null
        if (history.takeLastWhile { it.note == EMPTY }.size >= 3) return null
        return Auto(AgentAction.Wait, EMPTY)
    }

    private val generic = setOf("화면", "메뉴", "탭", "페이지", "설정", "보여", "열어", "앱")

    /**
     * "설정에서 블루투스 화면 열어줘": when exactly one item on screen is named by the target word
     * ("블루투스" row), open it. Switches are never pressed this way — that would change the setting.
     */
    private fun openNamedItem(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        if (ShortcutGoals.screenName(goal) == null || view.snapshot.home) return null
        val names = GoalText.targetWords(goal).filter { it !in generic && it.length >= 2 }
        if (names.isEmpty()) return null
        val candidates = view.elements.filter { e -> (e.kind == Kind.ITEM || e.kind == Kind.BUTTON) && e.enabled && !e.selected }
        val hits = candidates.filter { e -> names.any { n -> GoalText.variants(n).any { GoalText.normalize(e.label).startsWith(it) } } }
        // No item starts with the name: a single category that mentions it ("연결 Wi-Fi • 블루투스 • SIM 관리").
        val item = hits.singleOrNull()
            ?: candidates.filter { e -> GoalText.matches(e.label, names) }.singleOrNull()?.takeIf { hits.isEmpty() }
            ?: return null
        if (history.takeLast(2).any { it.action == "click \"${item.label}\"" }) return null
        return Auto(AgentAction.Click(item.id), "목표 이름 '${item.label}' 항목을 연다")
    }

    /**
     * For "X 화면 열어줘", the screen reached has to show X somewhere (a title, a selected tab) —
     * the model once accepted "배경화면 및 스타일" for "와이파이 화면".
     */
    fun screenShown(goal: String, view: ScreenView): Boolean {
        if (ShortcutGoals.screenName(goal) == null) return true
        val names = GoalText.targetWords(goal).filter { it !in generic && it.length >= 2 }
        if (names.isEmpty()) return true
        return GoalText.matches(view.snapshot.appLabel, names) || view.elements.any { e -> GoalText.matches(e.label, names) }
    }

    /** Selected tab or actual title, not a matching row in the previous menu. Any app can supply it. */
    fun openScreenEvidence(goal: String, view: ScreenView): Boolean {
        if (ShortcutGoals.screenName(goal) == null) return false
        val names = GoalText.targetWords(goal).filter { it !in generic && it.length >= 2 }
        if (names.isEmpty()) return false
        return view.elements.any { e ->
            (e.selected || (e.kind == Kind.TEXT && e.bounds.bottom <= view.snapshot.height * 0.30)) &&
                names.all { GoalText.matches(e.label, listOf(it)) }
        }
    }

    private val playLabel = Regex("^(?:재생|재생하기|play|resume)$", RegexOption.IGNORE_CASE)
    private val pauseLabel = Regex("^(?:일시정지|일시\\s*정지|정지|pause)$", RegexOption.IGNORE_CASE)
    private val nextLabel = Regex("^(?:다음\\s*곡|다음|next\\s*track|next|skip\\s*next)$", RegexOption.IGNORE_CASE)
    private val prevLabel = Regex("^(?:이전\\s*곡|이전|previous\\s*track|previous|prev|skip\\s*previous)$", RegexOption.IGNORE_CASE)

    /** True for a history line that pressed a next / previous control. */
    fun isSkip(action: String, next: Boolean): Boolean {
        val label = Regex("^click \"(.*)\"$").matchEntire(action)?.groupValues?.get(1)?.trim() ?: return false
        return (if (next) nextLabel else prevLabel).matches(label)
    }

    /**
     * Plain music commands on a player screen: press its own transport control. Media keys do not
     * reach a player inside Secure Folder; its play/pause/next buttons work the same in every app.
     */
    private fun mediaControl(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        val key = Router.mediaKeyIn(goal) ?: return null
        val pattern = when (key) { MediaKey.PLAY -> playLabel; MediaKey.PAUSE -> pauseLabel; MediaKey.NEXT -> nextLabel; MediaKey.PREVIOUS -> prevLabel }
        val control = view.elements.filter { it.kind == Kind.BUTTON || it.kind == Kind.ITEM }
            .filter { it.enabled && pattern.matches(it.label.trim()) }.singleOrNull() ?: return null
        if (history.takeLast(2).any { it.action == "click \"${control.label}\"" }) return null
        return Auto(AgentAction.Click(control.id), "음악 앱의 '${control.label}' 버튼")
    }

    private val addLabel = Regex("추가|담기|\\badd\\b",RegexOption.IGNORE_CASE)

    /** The add button that was pressed for [query], if any ("click "현재 재생목록에 추가 · 아이유(IU) - 좋은 날"" → 바뀜). */
    fun addedRow(query: String, history: List<HistoryLine>): String? = addedRows(query, history).lastOrNull()

    /** Every result row added for [query] so far, in order. */
    fun addedRows(query: String, history: List<HistoryLine>): List<String> = history.mapNotNull { h ->
        val label = Regex("^click \"(.*)\"$").matchEntire(h.action)?.groupValues?.get(1) ?: return@mapNotNull null
        val row = label.substringAfter(" · ", "")
        row.takeIf { addLabel.containsMatchIn(label.substringBefore(" · ")) && it.isNotEmpty() && h.outcome.contains("바뀜") &&
            GoalText.rowMatches(query, it) }
    }.distinct()

    /**
     * "<노래> 재생목록에 추가해줘" in a music app: type the song into its search field, press its search
     * button, then press the add button on the result row that names the song. The small model kept
     * pressing "재생목록 만들기" instead; these steps are the same in every player with a search box.
     */
    private val endGuidanceLabel = Regex("^(?:길|경로)?안내\\s*종료$")
    private val drivingControls = Regex("경로\\s*다시\\s*계산|reroute|(?:길|경로)?안내\\s*종료")

    /** "길안내 종료해줘", "안내 그만해", "내비 꺼줘". */
    fun isEndGuidance(goal: String): Boolean =
        Regex("(?:길\\s*안내|안내|내비(?:게이션)?|경로\\s*안내).{0,4}(?:종료|그만|꺼|끝내|멈춰|중지)").containsMatchIn(goal)

    /** NAVER Map is in front without any driving controls and nothing was pressed: there is no guidance to end. */
    fun noGuidance(goal: String, view: ScreenView, history: List<HistoryLine>): Boolean =
        isEndGuidance(goal) && view.snapshot.packageName == NavigationSession.NAVER_MAP && view.elements.size >= 5 &&
            view.elements.none { drivingControls.containsMatchIn(it.label) } && history.none { it.action.startsWith("click") }

    /** Guidance was ended: 안내 종료 was pressed and the driving controls are gone. */
    fun guidanceEnded(goal: String, view: ScreenView, history: List<HistoryLine>): Boolean =
        isEndGuidance(goal) && history.any { h -> h.action.startsWith("click") && endGuidanceLabel.matches(Regex("\"(.*)\"").find(h.action)?.groupValues?.get(1)?.trim().orEmpty()) &&
            h.outcome.contains("바뀜") } && view.elements.none { drivingControls.containsMatchIn(it.label) }

    /** NAVER keeps 안내 종료 in the driving screen's drawer: open it, then press 안내 종료 (and its confirmation). */
    private fun endGuidance(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        if (!isEndGuidance(goal)) return null
        val pressed = history.count { it.action.startsWith("click") && it.action.contains("종료") }
        if (view.snapshot.packageName != NavigationSession.NAVER_MAP && pressed == 0 && history.none { it.action.startsWith("open_app") })
            return Auto(AgentAction.OpenApp("네이버 지도"), "길안내는 네이버 지도에서 종료")
        if (view.snapshot.home) return null
        if (pressed >= 3) return null
        view.elements.firstOrNull { it.enabled && it.kind != Kind.TEXT && endGuidanceLabel.matches(it.label.trim()) }?.let {
            return Auto(AgentAction.Click(it.id), "안내 종료")
        }
        if (pressed > 0) view.elements.firstOrNull { it.enabled && it.kind != Kind.TEXT && Regex("^(?:종료|확인)$").matches(it.label.trim()) }?.let {
            return Auto(AgentAction.Click(it.id), "안내 종료 확인")
        }
        if (view.elements.none { drivingControls.containsMatchIn(it.label) }) return null
        if (history.takeLast(2).count { it.action.contains("drawer") || it.action.contains("메뉴") } >= 2) return null
        return view.elements.firstOrNull { it.enabled && it.kind != Kind.TEXT && Regex("메뉴.*옵션|drawer", RegexOption.IGNORE_CASE).containsMatchIn(it.label) }
            ?.let { Auto(AgentAction.Click(it.id), "안내 종료가 있는 메뉴 열기") }
    }

    /** The playlist row whose end (delete) icon was tapped for [query], if the tap changed the screen. */
    fun removedRow(query: String, history: List<HistoryLine>): String? = history.asReversed().firstNotNullOfOrNull { h ->
        val label = Regex("^click_end \"(.*)\"$").matchEntire(h.action)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
        label.takeIf { h.outcome.contains("바뀜") && GoalText.rowMatches(query, it) }
    }

    /**
     * "<노래> 재생목록에서 빼줘": find the playlist row naming the song (scrolling its list) and tap the
     * delete icon at the row's end. ClipStream draws that icon inside the row button without a node.
     */
    private fun playlistRemove(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        val query = GoalText.playlistRemove(goal) ?: return null
        if (view.snapshot.home || removedRow(query, history) != null) return null
        val wanted = query.trim().split(Regex("\\s+")).size
        fun isRow(e: Element) = (e.kind == Kind.BUTTON || e.kind == Kind.ITEM) && e.enabled && !e.label.contains(" · ") &&
            e.bounds.width * 2 >= view.snapshot.width && e.bounds.height >= 80
        val rows = view.elements.filter(::isRow)
        fun area(e: Element) = e.bounds.width.toLong() * e.bounds.height
        // The innermost list around a row is the one that scrolls it (the page itself scrolls too).
        fun box(row: Element) = view.lists.filter { l -> row.bounds.centerY in l.bounds.top..l.bounds.bottom &&
            row.bounds.centerX in l.bounds.left..l.bounds.right }.minByOrNull(::area)
        val scrolls = history.count { it.action.startsWith("scroll") }
        val stuck = history.lastOrNull()?.let { it.action.startsWith("scroll") && it.outcome.startsWith("변화 없음") } == true
        rows.filter { GoalText.rowMatches(query, it.label) }.maxByOrNull { GoalText.rowScore(query, it.label) }?.let { row ->
            val list = box(row)
            // Half hidden under the mini player: the end of the row may be another control. Move it into view first.
            if (list != null && (row.bounds.top < list.bounds.top || row.bounds.bottom > list.bounds.bottom)) {
                if (scrolls >= 6 || stuck) return null
                return Auto(AgentAction.Scroll(if (row.bounds.centerY > list.bounds.centerY) ScrollDir.DOWN else ScrollDir.UP, list.id),
                    "'${row.label.take(20)}' 줄이 잘려 보여 목록을 움직임")
            }
            if (history.count { it.action == "click_end \"${row.label}\"" } >= 2) return null
            return Auto(AgentAction.TapEnd(row.id), "재생목록의 '${row.label.take(30)}' 줄 끝 삭제 아이콘")
        }
        // Not visible: scroll the innermost list holding playlist rows, a few times at most.
        if (scrolls >= 6 || stuck) return null
        val list = rows.mapNotNull(::box).minByOrNull(::area) ?: return null
        return Auto(AgentAction.Scroll(ScrollDir.DOWN, list.id), "재생목록에서 '$query' 찾기")
    }

    private fun playlistAdd(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? =
        GoalText.playlistAdd(goal)?.let { addFlow(it, GoalText.playlistAddCount(goal), view, history) }

    private fun isTrackRow(view: ScreenView, e: Element) = (e.kind == Kind.BUTTON || e.kind == Kind.ITEM) && e.enabled &&
        !e.label.contains(" · ") && e.bounds.width * 2 >= view.snapshot.width && e.bounds.height >= 80

    /** A music player screen: its previous/next and play/pause controls are on it. */
    fun isPlayer(view: ScreenView): Boolean {
        val labels = view.elements.filter { it.kind == Kind.BUTTON || it.kind == Kind.ITEM }.map { it.label.trim() }
        return labels.any { prevLabel.matches(it) || nextLabel.matches(it) } && labels.any { playLabel.matches(it) || pauseLabel.matches(it) }
    }

    /**
     * The player bar at the bottom names the song. Read from raw nodes: a crowded screen drops plain
     * text from the compact view (70 elements). Search-result rows drawn behind the bar have an add
     * button beside their title; the bar's title has none.
     */
    private fun barTitle(query: String, view: ScreenView): Boolean {
        val nodes = view.snapshot.nodes
        val bottom = view.snapshot.height * 0.86
        return nodes.withIndex().any { (i, n) ->
            n.bounds.top >= bottom && n.bounds.top < view.snapshot.height && !n.clickable && n.ownLabel.isNotBlank() &&
                GoalText.rowMatches(query, n.ownLabel) &&
                nodes.withIndex().none { (j, m) -> j != i && m.parent == n.parent && addLabel.containsMatchIn(m.ownLabel) }
        }
    }

    /** The requested song is in the player bar and the pause control shows: it is playing. */
    fun nowPlaying(query: String, view: ScreenView): Boolean =
        barTitle(query, view) && view.elements.any { it.enabled && pauseLabel.matches(it.label.trim()) }

    /**
     * "늙은 사랑 틀어줘" in a player: tap the playlist row naming the song (scrolling its list down, then
     * up); a song not in the playlist is searched and added first, then played from the playlist.
     */
    private fun playSong(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        val query = GoalText.playSong(goal) ?: return null
        if (!isPlayer(view) || nowPlaying(query, view)) return null
        // The player bar updates a moment after the row's second tap: wait for it instead of asking the model.
        val lastTap = history.lastOrNull { it.action != "wait" }
        if (lastTap != null && lastTap.action.startsWith("double_tap") && !lastTap.outcome.startsWith("실행 실패")) {
            if (history.takeLast(3).count { it.action == "wait" } < 3) return Auto(AgentAction.Wait, "재생 막대가 바뀔 때까지 기다림")
        }
        // Selected but paused: press play.
        if (barTitle(query, view)) view.elements.firstOrNull { it.enabled && playLabel.matches(it.label.trim()) }
            ?.takeIf { b -> history.takeLast(2).none { it.action == "click \"${b.label}\"" } }
            ?.let { return Auto(AgentAction.Click(it.id), "고른 곡이 멈춰 있어 재생") }
        fun area(e: Element) = e.bounds.width.toLong() * e.bounds.height
        fun box(row: Element) = view.lists.filter { l -> row.bounds.centerY in l.bounds.top..l.bounds.bottom &&
            row.bounds.centerX in l.bounds.left..l.bounds.right }.minByOrNull(::area)
        val rows = view.elements.filter { isTrackRow(view, it) }
        // Scrolls since the last add: the added song lands at the end of the playlist.
        val since = history.indexOfLast { h -> addLabel.containsMatchIn(h.action.substringBefore(" · ")) && h.action.startsWith("click") } + 1
        val scrolls = history.drop(since).filter { it.action.startsWith("scroll") }
        rows.filter { GoalText.rowMatches(query, it.label) }.maxByOrNull { GoalText.rowScore(query, it.label) }?.let { row ->
            val list = box(row)
            if (list != null && (row.bounds.top < list.bounds.top || row.bounds.bottom > list.bounds.bottom) && scrolls.size < 12)
                return Auto(AgentAction.Scroll(if (row.bounds.centerY > list.bounds.centerY) ScrollDir.DOWN else ScrollDir.UP, list.id),
                    "'${row.label.take(20)}' 줄이 잘려 보여 목록을 움직임")
            if (history.count { it.action == "double_tap \"${row.label}\"" } >= 2) return null
            return Auto(AgentAction.DoubleTap(row.id), "재생목록의 '${row.label.take(30)}' 재생 (첫 탭 선택, 두 번째 탭 재생)")
        }
        // Not on screen: look down the playlist, then up, then search and add it.
        val list = rows.mapNotNull(::box).minByOrNull(::area)
        val downDone = scrolls.any { it.action.startsWith("scroll down") && it.outcome.startsWith("변화 없음") }
        val upDone = scrolls.any { it.action.startsWith("scroll up") && it.outcome.startsWith("변화 없음") }
        if (list != null && scrolls.size < 12 && !(downDone && upDone))
            return Auto(AgentAction.Scroll(if (downDone) ScrollDir.UP else ScrollDir.DOWN, list.id), "재생목록에서 '$query' 찾기")
        if (addedRows(query, history).isNotEmpty()) return null
        return addFlow(query, 1, view, history)
    }

    private fun addFlow(query: String, count: Int, view: ScreenView, history: List<HistoryLine>): Auto? {
        val added = addedRows(query, history)
        if (view.snapshot.home || added.size >= count) return null
        val adds = view.elements.filter { (it.kind == Kind.BUTTON || it.kind == Kind.ITEM) && it.enabled && it.label.contains(" · ") &&
            addLabel.containsMatchIn(it.label.substringBefore(" · ")) }
        val q = GoalText.normalize(query)
        val inputs = view.inputs.filter { it.enabled }
        val field = inputs.firstOrNull { Regex("검색|search|찾기", RegexOption.IGNORE_CASE).containsMatchIn(it.label) }
            ?: inputs.minByOrNull { it.bounds.top }
        // Results on screen belong to the query in the field only after its search ran: "아이유 드라마"
        // typed over the results of "김명기 say yes" left those stale results (and the model added one).
        val typedAt = history.indexOfLast { it.action.startsWith("type ") && GoalText.normalize(it.action).contains(q) }
        val searchPending = field != null && (!GoalText.normalize(field.value).contains(q) ||
            (typedAt >= 0 && history.drop(typedAt + 1).none { Regex("^click \"(?:검색|search)\"$", RegexOption.IGNORE_CASE).matches(it.action) }))
        if (adds.isNotEmpty() && !searchPending) {
            // "아이유 노래 3곡": the best-matching rows not added yet, top of the results first.
            val best = adds.filter { a -> a.label.substringAfter(" · ") !in added && history.none { it.action == "click \"${a.label}\"" } }
                .sortedByDescending { GoalText.rowScore(query, it.label.substringAfter(" · ")) }.firstOrNull()
            if (best != null && GoalText.rowMatches(query, best.label.substringAfter(" · ")))
                return Auto(AgentAction.Click(best.id), "검색 결과 '${best.label.substringAfter(" · ").take(30)}'를 재생목록에 추가")
        }
        if (added.isNotEmpty()) return null // fewer matching results than asked: keep what was added
        if (field == null) return null
        if (!GoalText.normalize(field.value).contains(q)) {
            if (history.count { it.action.startsWith("type ") && GoalText.normalize(it.action).contains(q) } >= 2) return null
            return Auto(AgentAction.Type(field.id, query, true), "검색창에 '$query' 입력")
        }
        if (adds.isNotEmpty() && !searchPending) return null // results are shown but none names the song: the model scrolls or rephrases
        val button = view.elements.firstOrNull { it.kind == Kind.BUTTON && it.enabled && Regex("^(?:검색|search)$", RegexOption.IGNORE_CASE).matches(it.label.trim()) }
            ?: return null
        if (history.takeLast(2).any { it.action == "click \"${button.label}\"" }) return null
        return Auto(AgentAction.Click(button.id), "'$query' 검색 실행")
    }

    /** For a search goal, the query has to be visible (typed in a field or shown in results). */
    fun searchShown(goal: String, view: ScreenView): Boolean {
        val q = ShortcutGoals.literalSearch(goal)?.let(GoalText::normalize) ?: return true
        return view.elements.any { GoalText.normalize(it.label).contains(q) || GoalText.normalize(it.value).contains(q) }
    }

    fun searchPrefixShown(goal:String,view:ScreenView):Boolean {
        val q=ShortcutGoals.searchPrefix(goal)?.let(GoalText::normalize)?:return false
        return view.elements.any { GoalText.normalize(it.label).contains(q) || GoalText.normalize(it.value).contains(q) }
    }

    /** A list that did not move was already at its end: try the other direction once. */
    private fun reverseScroll(history: List<HistoryLine>): Auto? {
        val last = history.lastOrNull() ?: return null
        val m = Regex("^scroll (down|up)(?: \"(.*)\")?$").matchEntire(last.action) ?: return null
        if (!last.outcome.startsWith("변화 없음")) return null
        if (history.takeLast(4).count { it.action.startsWith("scroll") && it.outcome.startsWith("변화 없음") } >= 2) return null
        val dir = if (m.groupValues[1] == "down") ScrollDir.UP else ScrollDir.DOWN
        return Auto(AgentAction.Scroll(dir, null), "목록 끝이라 반대 방향으로 스크롤")
    }

    /**
     * "X에서 Y 검색해줘": once inside an app, type Y into its search field (opening the field first if
     * only a search button is visible). The model kept pressing unrelated buttons on search screens.
     */
    private fun search(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        val query = ShortcutGoals.searchPrefix(goal) ?: return null
        if (view.snapshot.home || history.none { it.action.startsWith("open_app") || it.action.startsWith("click") }) return null
        val q = GoalText.normalize(query)
        if (history.any { it.action.startsWith("type ") && GoalText.normalize(it.action).contains(q) }) return null // typed already
        val inputs = view.inputs.filter { it.enabled }
        val field = inputs.firstOrNull { Regex("검색|search|찾기", RegexOption.IGNORE_CASE).containsMatchIn(it.label) } ?: inputs.singleOrNull()
        if (field != null) {
            if (GoalText.normalize(field.value).contains(q)) return null
            return Auto(AgentAction.Type(field.id, query, true), "검색창에 '$query' 입력")
        }
        val recent = history.takeLast(2).map { it.action }
        val button = view.elements.firstOrNull { e ->
            e.kind != Kind.TEXT && e.kind != Kind.INPUT && e.enabled && e.label.length <= 15 &&
                Regex("검색|search", RegexOption.IGNORE_CASE).containsMatchIn(e.label) &&
                !Regex("음성|AI|이미지|카메라|voice|image", RegexOption.IGNORE_CASE).containsMatchIn(e.label) &&
                recent.none { it == "click \"${e.label}\"" }
        } ?: return null
        return Auto(AgentAction.Click(button.id), "검색창을 열기 위해 '${button.label}'")
    }

    /**
     * "Pick the target, then confirm": once the goal's target has just been pressed ("회사") and exactly
     * one enabled finishing button that itself names the goal's action is on screen ("안내시작" for
     * "안내해줘"), press it. The next screen often shows the target only as an address, which made the
     * model think the target was still unset and go back to change it.
     */
    private fun commitAfterTarget(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        if (GoalText.searchQuery(goal) != null) return null // searches are handled by search()
        val words = GoalText.targetWords(goal)
        if (words.isEmpty()) return null
        val lastAct = history.lastOrNull { it.note != LOADING } ?: return null
        val target = Regex("^click \"(.*)\"$").matchEntire(lastAct.action)?.groupValues?.get(1) ?: return null
        if (!lastAct.outcome.contains("바뀜") || !GoalText.matches(target, words)) return null
        val commits = view.elements.filter { e ->
            e.kind != Kind.TEXT && e.enabled && Commit.isCommit(e.label) && GoalText.matches(e.label, words) && e.label != target
        }
        val button = commits.singleOrNull() ?: return null
        return Auto(AgentAction.Click(button.id), "목표 대상 '$target'을 골랐으니 '${button.label}' 확정")
    }

    private const val LOADING = "확정 버튼이 아직 눌리지 않는 상태라 기다림"

    /**
     * A finishing button ("안내시작") shown only as plain text means the screen is still loading (the
     * route is being computed). Pressing something else in that moment is how the agent swapped origin
     * and destination, so wait — at most three times in a row.
     */
    private fun commitLoading(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        if (GoalScope.multiple(goal) || ShortcutGoals.navigationTarget(goal)==null) return null
        if (history.takeLastWhile { it.note == LOADING }.size >= 3) return null
        if (history.count { it.note == LOADING } >= 6) return null
        // Only right after something was picked (a click that changed the screen), as in "회사" → route.
        val lastAct = history.lastOrNull { it.note != LOADING } ?: return null
        if (!lastAct.action.startsWith("click") || !lastAct.outcome.contains("바뀜")) return null
        // Only a finishing button that names the goal's own action ("안내시작" for "안내해줘") counts;
        // a timer's idle "시작" text is not a loading screen.
        val words = GoalText.targetWords(goal)
        val pending = view.elements.filter { it.kind == Kind.TEXT && Commit.isCommit(it.label) && GoalText.matches(it.label, words) }
        if (pending.isEmpty()) return null
        val bare = { s: String -> GoalText.normalize(s).replace(Regex("\\d+"), "") }
        val ready = view.elements.any { e -> e.kind != Kind.TEXT && e.enabled && pending.any { bare(it.label) == bare(e.label) } }
        return if (ready) null else Auto(AgentAction.Wait, LOADING)
    }

    /**
     * "지난 안내를 이어서 받으시겠습니까?" style prompts: a new command means a new task, so decline.
     * Not applied when the command itself asks to continue ("이어서 안내해줘").
     */
    private fun resumePrompt(goal: String, view: ScreenView): Auto? {
        if (wantsResume.containsMatchIn(goal)) return null
        val question = view.elements.firstOrNull { resumeQuestion.containsMatchIn(it.label) && asking.containsMatchIn(it.label) } ?: return null
        val no = view.elements.firstOrNull { it.id != question.id && it.kind != Kind.TEXT && decline.matches(it.label.trim()) } ?: return null
        return Auto(AgentAction.Click(no.id), "이전 작업을 이어갈지 묻는 창이라 '${no.label}' 선택 (새 명령)")
    }
}
