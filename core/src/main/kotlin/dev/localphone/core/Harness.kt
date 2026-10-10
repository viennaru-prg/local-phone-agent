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
    fun authScreen(view: ScreenView): Boolean = view.snapshot.packageName != "com.android.settings.intelligence" &&
        // Secure Folder's own lock ("잠금해제 패턴을 그리세요") is drawn by the Settings package itself.
        view.elements.any { it.kind == Kind.TEXT && auth.containsMatchIn(it.label) }

    /**
     * "<앱> 열어줘" and the app's icon is on screen (Secure Folder's app list after unlocking): tap it.
     * Names match across scripts ("클립스트림" ~ "Clipstream Player").
     */
    private fun openAppIcon(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        val name = DirectGoals.appName(goal) ?: GoalText.playSong(goal)?.let { AppProfiles.appFor("music") } ?: return null
        if (view.snapshot.home || GoalText.normalize(view.snapshot.appLabel).contains(GoalText.normalize(name)) ||
            GoalText.soundsLike(name, view.snapshot.appLabel)) return null
        val icon = view.elements.filter { e -> e.enabled && (e.kind == Kind.ITEM || e.kind == Kind.BUTTON) &&
            (GoalText.normalize(e.label) == GoalText.normalize(name) || GoalText.soundsLike(name, e.label)) }.singleOrNull() ?: return null
        if (history.takeLast(2).any { it.action == "click \"${icon.label}\"" }) return null
        return Auto(AgentAction.Click(icon.id), "'${icon.label}' 앱 아이콘")
    }

    /** Returns an action to take without the model, or null to let the model decide. */
    fun preDecide(goal: String, view: ScreenView, history: List<HistoryLine> = emptyList()): Auto? {
        emptyScreen(view, history)?.let { return it }
        openAppIcon(goal, view, history)?.let { return it }
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
        searchScreen(goal, view, history)?.let { return it }
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

    private val generic = setOf("화면", "메뉴", "탭", "페이지", "설정", "보여", "열어", "들어", "들어가", "앱")

    /**
     * "설정에서 블루투스 화면 열어줘": when exactly one item on screen is named by the target word
     * ("블루투스" row), open it. Switches are never pressed this way — that would change the setting.
     */
    private fun openNamedItem(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        if (ShortcutGoals.screenName(goal) == null || view.snapshot.home) return null
        val names = GoalText.targetWords(goal).filter { it !in generic && it.length >= 2 }
        if (names.isEmpty()) return null
        val candidates = view.elements.filter { e -> (e.kind == Kind.ITEM || e.kind == Kind.BUTTON) && e.enabled && !e.selected }
        // Every word of the name, not one of them: "보안 및 개인정보 보호" is not "소프트웨어 정보".
        fun named(e: Element) = names.all { GoalText.matches(e.label, listOf(it)) }
        val hits = candidates.filter { e -> named(e) && GoalText.variants(names.first()).any { GoalText.normalize(e.label).startsWith(it) } }
        // Search results list "디스플레이" next to "디스플레이 · 최근 사용한 설정": the exact name wins.
        val exact = ShortcutGoals.screenName(goal)?.let(GoalText::normalize)
        val tab = Regex("탭|tab", RegexOption.IGNORE_CASE)
        val item = hits.singleOrNull { GoalText.normalize(it.label) == exact }
            // A row may carry its container's name after " · " ("배터리 정보 · gesture controller view"), and
            // the same row may be listed under two sections (폰 정보 and 배터리 both list 배터리 정보): either one.
            ?: hits.filter { GoalText.normalize(it.label.substringBefore(" · ")) == exact }.takeIf { same ->
                same.map { GoalText.normalize(it.label) }.distinct().size == 1 }?.firstOrNull()
            // "저장 탭 열어줘": the bottom tab "저장 탭 저장", not the map's "저장 레이어 끄기".
            ?: hits.takeIf { tab.containsMatchIn(goal) }?.singleOrNull { tab.containsMatchIn(it.label) }
            ?: hits.singleOrNull()
            // No item starts with the name: a single category that mentions it ("연결 Wi-Fi • 블루투스 • SIM 관리").
            ?: candidates.filter(::named).singleOrNull()?.takeIf { hits.isEmpty() }
            ?: return null
        // Once more only if that press led to another screen that lists the item again (a settings search
        // result opens the parent page "폰 정보" with "소프트웨어 정보" highlighted in it).
        val same = history.takeLast(3).filter { it.action == "click \"${item.label}\"" }
        if (same.size >= 2 || same.any { "바뀜" !in it.outcome }) return null
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
        // Every word in one label: "소프트웨어 정보" is not shown by "보안 및 개인정보 보호" (only "정보").
        fun named(label: String) = names.all { GoalText.matches(label, listOf(it)) }
        return named(view.snapshot.appLabel) || view.elements.any { e -> named(e.label) }
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

    /**
     * The requested screen is still a row to press on this one ("폰 정보" listing "소프트웨어 정보"): not
     * open yet, whatever a verifier reads into the highlighted row.
     */
    fun screenStillListed(goal: String, view: ScreenView): Boolean {
        if (ShortcutGoals.screenName(goal) == null || openScreenEvidence(goal, view)) return false
        val names = GoalText.targetWords(goal).filter { it !in generic && it.length >= 2 }
        if (names.isEmpty()) return false
        return view.elements.any { e ->
            (e.kind == Kind.ITEM || e.kind == Kind.BUTTON) && e.enabled && names.all { GoalText.matches(e.label, listOf(it)) } &&
                GoalText.variants(names.first()).any { GoalText.normalize(e.label).startsWith(it) }
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
        // The key already went out and no player is on screen: give the system audio state time to catch
        // up (a Secure Folder player lags) before anything goes looking for the player.
        if (!isPlayer(view) && history.any { it.action.startsWith("media") }) {
            if (Skills.waitsSince(history) < 2) return Auto(AgentAction.Wait, "보낸 재생 키가 반영될 때까지 기다림")
            // The key did not reach the player (Secure Folder): open the music app and use its own buttons.
            AppProfiles.appFor("music")?.takeIf { history.none { h -> h.action.startsWith("open_app") } }
                ?.let { return Auto(AgentAction.OpenApp(it), "재생 키가 닿지 않아 음악 앱을 열어 화면 버튼 사용") }
        }
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
    /** "길안내 종료해줘", "안내 그만해", "내비 꺼줘". */
    fun isEndGuidance(goal: String): Boolean =
        Regex("(?:길\\s*안내|안내|내비(?:게이션)?|경로\\s*안내).{0,4}(?:종료|그만|꺼|끝내|멈춰|중지)").containsMatchIn(goal)

    /** The navigation app (profile role "navigation"); NAVER Map when no profile names one. */
    fun navigationApp(): AppProfile = AppProfiles.forRole("navigation") ?: AppProfiles.forPackage(NavigationSession.NAVER_MAP)

    /** The navigation app is in front without any driving controls and nothing was pressed: there is no guidance to end. */
    fun noGuidance(goal: String, view: ScreenView, history: List<HistoryLine>): Boolean {
        val app = navigationApp()
        return isEndGuidance(goal) && view.snapshot.packageName == app.packageName && view.elements.size >= 5 &&
            view.elements.none { app.activeGuidanceRegex().containsMatchIn(it.label) } && history.none { it.action.startsWith("click") } &&
            settledInNavigationApp(view, history)
    }

    /**
     * The map just opened shows its home (with a "splash" layer) for a moment before it returns to the
     * running guidance: "길안내 중이 아니에요" was said while guidance was on. Judge only after a few waits.
     */
    private fun settledInNavigationApp(view: ScreenView, history: List<HistoryLine>): Boolean {
        val loading = view.elements.any { Regex("^splash$|로딩|loading", RegexOption.IGNORE_CASE).containsMatchIn(it.label.trim()) }
        return Skills.waitsSince(history) >= (if (loading) 4 else 2)
    }

    /** Guidance was ended: its end control was pressed and the driving controls are gone. */
    fun guidanceEnded(goal: String, view: ScreenView, history: List<HistoryLine>): Boolean {
        val app = navigationApp()
        return isEndGuidance(goal) && history.any { h -> h.action.startsWith("click") && h.outcome.contains("바뀜") &&
            app.endGuidanceRegex().matches(Regex("\"(.*)\"").find(h.action)?.groupValues?.get(1)?.trim().orEmpty()) } &&
            view.elements.none { app.activeGuidanceRegex().containsMatchIn(it.label) }
    }

    /** Ends guidance: open the navigation app if needed, press its end control (behind a menu if hidden), confirm. */
    private fun endGuidance(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        if (!isEndGuidance(goal)) return null
        val app = navigationApp()
        val ended = history.count { h -> h.action.startsWith("click") && app.endGuidanceRegex().matches(Regex("\"(.*)\"").find(h.action)?.groupValues?.get(1)?.trim().orEmpty()) }
        if (view.snapshot.packageName != app.packageName && ended == 0 && history.none { it.action.startsWith("open_app") })
            return app.names.firstOrNull()?.let { Auto(AgentAction.OpenApp(it), "길안내는 ${it}에서 종료") }
        if (view.snapshot.home || ended >= 3) return null
        if (ended > 0) view.elements.firstOrNull { it.enabled && it.kind != Kind.TEXT && Regex("^(?:종료|확인|예)$").matches(it.label.trim()) }?.let {
            return Auto(AgentAction.Click(it.id), "안내 종료 확인")
        }
        if (ended == 0 && view.elements.none { app.activeGuidanceRegex().containsMatchIn(it.label) }) {
            // No driving controls yet: give the map time to come back to its guidance screen before
            // concluding there is none (noGuidance needs these waits).
            return if (view.snapshot.packageName == app.packageName && !settledInNavigationApp(view, history))
                Auto(AgentAction.Wait, "지도 앱이 주행 화면으로 돌아올 때까지 기다림") else null
        }
        return Skills.pressBehindMenu(app.endGuidanceRegex(), view, history, "안내 종료")
    }

    /** The playlist row a delete action was performed on for [query] (either way), if the screen changed. */
    fun removedRow(query: String, history: List<HistoryLine>): String? = Skills.deletedRow(query, history)

    /** "<노래> 재생목록에서 빼줘": find the row naming the song and delete it the way this app deletes rows. */
    private fun playlistRemove(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        val query = GoalText.playlistRemove(goal, isPlayer(view)) ?: return null
        if (view.snapshot.home || removedRow(query, history) != null) return null
        return when (val found = Skills.findRow(query, view, history, maxScrolls = 6)) {
            is Skills.Found.Row -> Skills.deleteRow(AppProfiles.forPackage(view.snapshot.packageName), view, found.row, history)
            is Skills.Found.Move -> found.auto
            Skills.Found.Missing -> null
        }
    }

    private fun playlistAdd(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? =
        GoalText.playlistAdd(goal, isPlayer(view))?.let { addFlow(it, GoalText.playlistAddCount(goal), view, history) }

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

    /**
     * The song named in the player bar (bottom of the screen, no add button beside it): the longest such
     * text. For "지금 나오는 노래 뭐야?".
     */
    fun barSong(view: ScreenView): String? {
        if (!isPlayer(view)) return null
        val nodes = view.snapshot.nodes
        val bottom = view.snapshot.height * 0.86
        return nodes.withIndex().filter { (i, n) ->
            n.bounds.top >= bottom && n.bounds.top < view.snapshot.height && !n.clickable && n.ownLabel.length >= 3 &&
                !Regex("^[\\d:·\\s]+$").matches(n.ownLabel) &&
                nodes.withIndex().none { (j, m) -> j != i && m.parent == n.parent && addLabel.containsMatchIn(m.ownLabel) }
        }.maxByOrNull { it.value.ownLabel.length }?.value?.ownLabel?.trim()
    }

    /** The requested song is in the player bar and the pause control shows: it is playing. */
    /**
     * What the player on screen shows: true = playing (its pause control is offered), false = paused
     * (play control offered), null = no player or both/neither shown.
     */
    fun playerState(view: ScreenView): Boolean? {
        if (!isPlayer(view)) return null
        val controls = view.elements.filter { it.enabled && (it.kind == Kind.BUTTON || it.kind == Kind.ITEM) }.map { it.label.trim() }
        val pause = controls.any { pauseLabel.matches(it) }
        val play = controls.any { playLabel.matches(it) }
        return when { pause && !play -> true; play && !pause -> false; else -> null }
    }

    fun nowPlaying(query: String, view: ScreenView): Boolean =
        barTitle(query, view) && view.elements.any { it.enabled && pauseLabel.matches(it.label.trim()) }

    /**
     * "늙은 사랑 틀어줘" in a player: tap the playlist row naming the song (scrolling its list down, then
     * up); a song not in the playlist is searched and added first, then played from the playlist.
     */
    private fun playSong(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        val query = GoalText.playSong(goal) ?: return null
        if (!isPlayer(view)) return null
        // The bar already shows the song playing; the stream may need a moment before audio starts
        // (the loop completes once the system reports music). Waiting beats a 30 s model call.
        if (nowPlaying(query, view)) return if (Skills.waitsSince(history) < 5) Auto(AgentAction.Wait, "곡이 시작되어 소리가 날 때까지 기다림") else null
        // The player bar updates a moment after the row (or its play control) is pressed: wait for it.
        val lastTap = Skills.lastAction(history)
        val pressedPlay = lastTap?.action?.let { a -> Regex("^click \"(.*)\"$").matchEntire(a)?.groupValues?.get(1)?.let { playLabel.matches(it.trim()) } } == true
        if (lastTap != null && (lastTap.action.startsWith("double_tap") || pressedPlay ||
                (lastTap.action.startsWith("click \"") && GoalText.rowMatches(query, lastTap.action))) &&
            !lastTap.outcome.startsWith("실행 실패")) {
            // Up to three short waits; then a single tap that only selected the row escalates (Skills.playRow).
            if (Skills.waitsSince(history) < 3) return Auto(AgentAction.Wait, "재생 막대가 바뀔 때까지 기다림")
        }
        // Selected but paused: press play.
        if (barTitle(query, view)) view.elements.firstOrNull { it.enabled && playLabel.matches(it.label.trim()) }
            ?.takeIf { b -> history.takeLast(2).none { it.action == "click \"${b.label}\"" } }
            ?.let { return Auto(AgentAction.Click(it.id), "고른 곡이 멈춰 있어 재생") }
        // Scrolls since the last add: the added song lands at the end of the playlist.
        val since = history.indexOfLast { h -> addLabel.containsMatchIn(h.action.substringBefore(" · ")) && h.action.startsWith("click") } + 1
        return when (val found = Skills.findRow(query, view, history, since)) {
            is Skills.Found.Row -> Skills.playRow(AppProfiles.forPackage(view.snapshot.packageName), found.row, history)
            is Skills.Found.Move -> found.auto
            // Not in the playlist: search and add it, then it is played from the playlist.
            Skills.Found.Missing -> if (addedRows(query, history).isNotEmpty()) null else addFlow(query, 1, view, history)
        }
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
        return searchFor(query, view, history)
    }

    /** Type [query] into the app's search field, opening the field first if only a search button shows. */
    private fun searchFor(query: String, view: ScreenView, history: List<HistoryLine>): Auto? {
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
     * "설정에서 소프트웨어 정보 보여줘": a screen that is not on the app's first page is found by the app's
     * own search, not by browsing menus (the model opened "보안 및 개인정보 보호" for the word "정보").
     * Only from the first page, before anything else was tried; the result row is opened by
     * [openNamedItem].
     */
    private fun searchScreen(goal: String, view: ScreenView, history: List<HistoryLine>): Auto? {
        val name = ShortcutGoals.screenName(goal) ?: return null
        if (GoalText.namedApp(goal) == null || view.snapshot.home || history.none { it.action.startsWith("open_app") }) return null
        // Something here already carries the name (two "저장…" controls the harness could not tell apart):
        // that is the model's choice to make, not a reason to search.
        val names = GoalText.targetWords(goal).filter { it !in generic && it.length >= 2 }
        if (view.elements.any { e -> e.kind != Kind.INPUT && names.isNotEmpty() && names.all { GoalText.matches(e.label, listOf(it)) } }) return null
        val since = history.indexOfLast { it.action.startsWith("open_app") }
        val searching = Regex("^(?:type |click \"[^\"]*(?:검색|search))", RegexOption.IGNORE_CASE)
        if (history.drop(since + 1).any { Skills.realAction.containsMatchIn(it.action) && it.action != "wait" && !searching.containsMatchIn(it.action) }) return null
        return searchFor(name, view, history)
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
