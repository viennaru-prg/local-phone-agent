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
    fun authScreen(view: ScreenView): Boolean = view.elements.any { auth.containsMatchIn(it.label) }

    /** Returns an action to take without the model, or null to let the model decide. */
    fun preDecide(goal: String, view: ScreenView, history: List<HistoryLine> = emptyList()): Auto? {
        emptyScreen(view, history)?.let { return it }
        mediaControl(goal, view, history)?.let { return it }
        playlistAdd(goal, view, history)?.let { return it }
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
        val key = Router.simpleMediaKey(goal) ?: return null
        val pattern = when (key) { MediaKey.PLAY -> playLabel; MediaKey.PAUSE -> pauseLabel; MediaKey.NEXT -> nextLabel; MediaKey.PREVIOUS -> prevLabel }
        val control = view.elements.filter { it.kind == Kind.BUTTON || it.kind == Kind.ITEM }
            .filter { it.enabled && pattern.matches(it.label.trim()) }.singleOrNull() ?: return null
        if (history.takeLast(2).any { it.action == "click \"${control.label}\"" }) return null
        return Auto(AgentAction.Click(control.id), "음악 앱의 '${control.label}' 버튼")
    }

    private val addLabel = Regex("추가|담기|\\badd\\b",RegexOption.IGNORE_CASE)

    /** The add button that was pressed for [query], if any ("click "현재 재생목록에 추가 · 아이유(IU) - 좋은 날"" → 바뀜). */
    fun addedRow(query: String, history: List<HistoryLine>): String? = history.asReversed().firstNotNullOfOrNull { h ->
        val label = Regex("^click \"(.*)\"$").matchEntire(h.action)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
        val row = label.substringAfter(" · ", "")
        row.takeIf { addLabel.containsMatchIn(label.substringBefore(" · ")) && it.isNotEmpty() && h.outcome.contains("바뀜") &&
            GoalText.rowScore(query, it) * 2 >= query.trim().split(Regex("\\s+")).size }
    }

    /**
     * "<노래> 재생목록에 추가해줘" in a music app: type the song into its search field, press its search
     * button, then press the add button on the result row that names the song. The small model kept
     * pressing "재생목록 만들기" instead; these steps are the same in every player with a search box.
     */
    private fun playlistAdd(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        val query = GoalText.playlistAdd(goal) ?: return null
        if (view.snapshot.home || addedRow(query, history) != null) return null
        val wanted = query.trim().split(Regex("\\s+")).size
        val adds = view.elements.filter { (it.kind == Kind.BUTTON || it.kind == Kind.ITEM) && it.enabled && it.label.contains(" · ") &&
            addLabel.containsMatchIn(it.label.substringBefore(" · ")) }
        if (adds.isNotEmpty()) {
            val best = adds.maxByOrNull { GoalText.rowScore(query, it.label.substringAfter(" · ")) }!!
            if (GoalText.rowScore(query, best.label.substringAfter(" · ")) * 2 >= wanted &&
                history.none { it.action == "click \"${best.label}\"" })
                return Auto(AgentAction.Click(best.id), "검색 결과 '${best.label.substringAfter(" · ").take(30)}'를 재생목록에 추가")
        }
        val q = GoalText.normalize(query)
        val inputs = view.inputs.filter { it.enabled }
        val field = inputs.firstOrNull { Regex("검색|search|찾기", RegexOption.IGNORE_CASE).containsMatchIn(it.label) }
            ?: inputs.minByOrNull { it.bounds.top } ?: return null
        if (!GoalText.normalize(field.value).contains(q)) {
            if (history.count { it.action.startsWith("type ") && GoalText.normalize(it.action).contains(q) } >= 2) return null
            return Auto(AgentAction.Type(field.id, query, true), "검색창에 '$query' 입력")
        }
        if (adds.isNotEmpty()) return null // results are shown but none names the song: the model scrolls or rephrases
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
