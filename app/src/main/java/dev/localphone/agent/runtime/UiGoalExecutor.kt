package dev.localphone.agent.runtime

import android.net.Uri
import dev.localphone.agent.AgentApplication
import dev.localphone.agent.data.*
import dev.localphone.core.*
import kotlinx.coroutines.*

data class UiGoalResult(val message: String, val evidence: String, val coordinates: Coordinates? = null,
                        val reference: ProviderPlaceReference? = null)

/** NAVER-specific semantics accelerate a goal; every action still resolves an element on the live screen. */
class NaverUiGoal(private val graph: AgentApplication, private val navigation: NaverNavigation) {
    private val cache = ProviderPlaceCache(graph.settings)
    suspend fun navigate(phrase: String, known: PlaceCandidate?, structuredLaunched: Boolean): UiGoalResult {
        val slot = PlaceSlots.slotFor(phrase)
        var cached = if (known == null) cache.get(phrase) else null
        val originalQuery = PlaceText.variants(phrase).minBy { it.length }.let { if (slot != null) PlaceSlots.aliases.getValue(slot).first() else stripParticle(phrase) }
        var query = cached?.name?.takeUnless { PlaceSlots.slotFor(it) != null } ?: originalQuery
        val uiGoal = "$phrase 안내 시작. ${known?.name.orEmpty()} ${cached?.name.orEmpty()} $query"
        val result = graph.uiAutomation.run(NaverLinks.PACKAGE, uiGoal) {
            progress("지도에서 목적지 찾는 중…")
            if (!structuredLaunched && !navigation.openMap()) throw UiUnavailable(InvocationState.EXECUTION_FAILED, "이 설치 공간의 네이버지도를 열지 못했습니다.")
            var selectedName = known?.name
            var selectedAddress = known?.address.orEmpty()
            var selectedCoordinates = known?.coordinates
            var savedAttempted = false
            val visitedAreas = mutableSetOf<String>()
            val visitedEntries = mutableSetOf<String>()
            var currentArea = "map"
            val areaScrolls = mutableMapOf<String, Int>()
            var personalBack = false
            var cachedMisses = 0
            var searchAttempted = false
            var searchRetried = false
            var searchedPersonal = false
            var idle = 0
            var startClicked = false
            var resolved = known != null
            var destinationObserved = false
            val seen = mutableSetOf<Int>()
            val history = mutableListOf<String>()
            // App-specific labels are accelerators too. Continue on the observed UI with the local
            // planner when those labels do not match; it cannot guess a private destination identity.
            suspend fun assist(screen: UiScreen, identityResolved: Boolean): Boolean {
                val proposal = graph.planner.nextUi(uiGoal, screen, history.takeLast(6))
                val command = (proposal as? UiProposal.Act)?.command ?: return false
                val node = when (command) {
                    is UiCommand.Click -> screen.node(command.token)
                    is UiCommand.SetText -> screen.node(command.token)
                    is UiCommand.Submit -> screen.node(command.token)
                    is UiCommand.Scroll -> screen.node(command.token)
                    UiCommand.Back -> null
                }
                if (!identityResolved) {
                    val discovery = when (command) {
                        is UiCommand.Click -> node != null && Regex("저장|즐겨찾기|내 장소|집.?회사|자주.*(?:가는|찾는)|MY|마이|메뉴|더보기|검색|Search|Saved|Frequent", RegexOption.IGNORE_CASE).containsMatchIn(node.label)
                        is UiCommand.SetText -> PlaceText.normalize(command.text) == PlaceText.normalize(query)
                        is UiCommand.Submit -> node?.label?.contains(query) == true
                        is UiCommand.Scroll, UiCommand.Back -> true
                    }
                    if (!discovery) return false
                }
                if (!act(screen, command)) return false
                history += command.toString()
                if (identityResolved && command is UiCommand.Click && node != null &&
                    Regex("안내.*시작|주행.*시작|내비게이션.*시작|start.*(?:navigation|guidance)", RegexOption.IGNORE_CASE).containsMatchIn(node.label)) startClicked = true
                return true
            }
            repeat(35) {
                val screen = screen()
                val content = screen.text()
                val normalized = PlaceText.normalize(content)
                if (screen.nodes.any { it.role.contains("ProgressBar") } || Regex("불러오는중|로딩중|경로탐색중|계산중|loading", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) {
                    delay(450); return@repeat
                }
                if (resolved) {
                    val identity = listOfNotNull(selectedName, selectedAddress.takeIf(String::isNotBlank), known?.name)
                    if (identity.any { PlaceText.normalize(it).let { value -> value.isNotBlank() && normalized.contains(value) } }) destinationObserved = true
                    // A route preview or accepted click is not success. Observe guidance controls and driving status together.
                    if (destinationObserved && startClicked && SemanticUi.navigationStarted(screen)) {
                        val reference = ProviderPlaceReference(phrase, selectedName ?: phrase, selectedAddress,
                            NaverLinks.PACKAGE, System.currentTimeMillis())
                        return@run UiGoalResult("${selectedName ?: phrase} 안내 시작을 지도 화면에서 확인했습니다.",
                            "NAVIGATION_ACTIVE_AFTER_START", selectedCoordinates, reference)
                    }
                    progress("목적지 경로와 안내 시작 확인 중…")
                    val start = screen.exact("안내 시작", "주행 시작", "경로 안내 시작", "내비게이션 시작", "Start navigation", "Start guidance")
                        .map(screen::clickTarget).distinctBy { it.token }
                    if (destinationObserved && start.isNotEmpty() && !startClicked) {
                        val node = select(screen, start, "어느 안내 방식으로 시작할까요?") ?: return@repeat
                        startClicked = act(screen, UiCommand.Click(node.token)); idle = 0; return@repeat
                    }
                    if (startClicked) { delay(350); if (++idle < 9) return@repeat }
                    val route = screen.exact("도착", "목적지로", "길찾기", "경로 찾기", "자동차", "Destination", "Directions", "Driving")
                        .map(screen::clickTarget).distinctBy { it.token }
                    val node = route.firstOrNull()
                    if (node != null && act(screen, UiCommand.Click(node.token))) { idle = 0; return@repeat }
                    if (assist(screen, true)) { idle = 0; return@repeat }
                    if (++idle >= 5) throw UiUnavailable(InvocationState.EXECUTION_FAILED, "목적지는 찾았지만 지도 화면에서 안내 시작을 확인하지 못했습니다.")
                    delay(250); return@repeat
                }

                // Prefer NAVER's own home/work registration or frequent-place area over a generic
                // favorite with the same nickname. These tabs may be children of a clickable Compose View.
                if (!searchAttempted && cached == null) {
                    val preferred = NaverPersonalUi.areas(slot).first()
                    val tab = if (preferred.id !in visitedAreas) NaverPersonalUi.targets(screen, preferred.labels).firstOrNull() else null
                    if (tab != null) {
                        visitedAreas += preferred.id
                        if (act(screen, UiCommand.Click(tab.token))) {
                            currentArea = preferred.id; savedAttempted = true; idle = 0; return@repeat
                        }
                    }
                }
                val personalContext = !searchAttempted || searchedPersonal || normalized.contains("저장장소") ||
                    normalized.contains("저장한장소") || normalized.contains("자주가는곳")
                val lookup = cached?.name?.takeIf { searchAttempted } ?: if (slot != null) PlaceSlots.aliases.getValue(slot).first() else query
                val aliases = if (slot != null && !(cached != null && searchAttempted)) PlaceSlots.aliases.getValue(slot) else listOf(lookup)
                val exact = NaverPersonalUi.rows(screen, aliases)
                val preferredAddress = selectedAddress.takeIf(String::isNotBlank) ?: cached?.address?.takeIf { searchAttempted && it.isNotBlank() }
                val cachedExact = if (preferredAddress != null) exact.filter { node ->
                    PlaceText.normalize((listOf(node.label) + screen.descendants(node.token).map { it.label }).joinToString(" "))
                        .contains(PlaceText.normalize(preferredAddress))
                } else exact
                if (cachedExact.isNotEmpty()) {
                    val untrustedPrivate = slot != null && !personalContext && cached == null
                    val node = if (untrustedPrivate) {
                        // A public business called '회사' is not automatically the user's employer, even if unique.
                        chooseIdentity(screen, cachedExact, "저장 장소에서 회사를 찾지 못했습니다. 검색 결과 중 실제 회사가 어느 곳인가요?")
                    } else select(screen, cachedExact, "같은 이름의 목적지가 여러 개입니다. 어느 곳인가요?")
                    if (node != null) {
                        val labels = (listOf(node.label) + screen.descendants(node.token).map { it.label }).filter(String::isNotBlank).distinct()
                        selectedName = labels.firstOrNull { value -> aliases.any { PlaceText.normalize(value) == PlaceText.normalize(it) } } ?: lookup
                        selectedAddress = labels.firstOrNull { value -> Regex("(?:[가-힣]+(?:시|도|구|군|로|길)\\s)|주소:").containsMatchIn(value) }.orEmpty().removePrefix("주소: ")
                        selectedCoordinates = explicitCoordinates(UiScreen(screen.packageName, screen.revision,
                            listOf(node) + screen.descendants(node.token)))
                        if (act(screen, UiCommand.Click(node.token))) { resolved = true; destinationObserved = true; idle = 0; return@repeat }
                    }
                }
                if (cached != null && searchAttempted && cachedExact.isEmpty()) {
                    // A cached provider identity is only an accelerator. A search miss or changed
                    // address must re-open the provider's personal areas before asking the user.
                    if (++cachedMisses < 3) { delay(300); return@repeat }
                    cached = null; query = originalQuery; searchAttempted = false; searchRetried = false
                    searchedPersonal = false; savedAttempted = false; personalBack = false
                    visitedAreas.clear(); visitedEntries.clear(); areaScrolls.clear(); seen.clear(); idle = 0
                    if (navigation.openMap()) { delay(320); return@repeat }
                }
                if (!searchAttempted && cached == null) {
                    if (savedAttempted && areaScrolls.getOrDefault(currentArea, 0) < 3) {
                        val scroll = NaverPersonalUi.contentScroll(screen)
                        if (scroll != null && seen.add((currentArea + content).hashCode()) && act(screen, UiCommand.Scroll(scroll.token))) {
                            areaScrolls[currentArea] = areaScrolls.getOrDefault(currentArea, 0) + 1; return@repeat
                        }
                    }
                    for (area in NaverPersonalUi.areas(slot).filter { it.id !in visitedAreas }) {
                        val tab = NaverPersonalUi.targets(screen, area.labels).firstOrNull() ?: continue
                        visitedAreas += area.id
                        if (act(screen, UiCommand.Click(tab.token))) {
                            currentArea = area.id; savedAttempted = true; idle = 0; return@repeat
                        }
                    }
                    val entry = NaverPersonalUi.entries(screen).firstOrNull { PlaceText.normalize(it.label.ifBlank {
                        screen.descendants(it.token).joinToString(" ") { child -> child.label }
                    }) !in visitedEntries }
                    if (entry != null) {
                        visitedEntries += PlaceText.normalize(entry.label.ifBlank { screen.descendants(entry.token).joinToString(" ") { it.label } })
                        if (act(screen, UiCommand.Click(entry.token))) {
                            savedAttempted = true; currentArea = "saved"; idle = 0; return@repeat
                        }
                    }
                    val menu = NaverPersonalUi.targets(screen, listOf("메뉴", "더보기", "Menu")).firstOrNull()
                    if (menu != null && "menu" !in visitedEntries) {
                        visitedEntries += "menu"
                        if (act(screen, UiCommand.Click(menu.token))) { idle = 0; return@repeat }
                    }
                    // A saved list can hide MY/favorites behind it. Return once to discover the
                    // remaining root entry points, rather than declaring the private place missing.
                    if (savedAttempted && !personalBack && screen.nodes.none { it.editable }) {
                        personalBack = true
                        if (act(screen, UiCommand.Back)) { idle = 0; return@repeat }
                    }
                }
                if (!searchAttempted) {
                    progress("지도 검색에서 목적지 확인 중…")
                    val input = screen.nodes.firstOrNull { it.editable && Regex("검색|search", RegexOption.IGNORE_CASE).containsMatchIn(it.label + it.viewId) }
                    // Search within saved places if available; otherwise use the provider's own search UI/deep link.
                    searchedPersonal = input != null && Regex("(?:저장|즐겨찾기|자주.*가는|내 장소).*검색|(?:saved|frequent).*search", RegexOption.IGNORE_CASE).containsMatchIn(input.label)
                    searchAttempted = true
                    if (input != null && act(screen, UiCommand.SetText(input.token, query))) {
                        val updated = screen()
                        val search = updated.exact("검색", "Search").map(updated::clickTarget).firstOrNull()
                        if (search != null && act(updated, UiCommand.Click(search.token))) { idle = 0; return@repeat }
                        val refreshed = updated.nodes.firstOrNull { it.editable && it.token == input.token }
                        if (refreshed != null && act(updated, UiCommand.Submit(refreshed.token))) return@repeat
                    }
                    searchedPersonal = false
                    if (navigation.openSearch(query)) { idle = 0; return@repeat }
                }
                if (searchAttempted && !searchRetried && screen.nodes.any { it.editable }) {
                    searchRetried = true
                    val submit = screen.exact("검색", "Search").map(screen::clickTarget).firstOrNull()
                    if (submit != null && act(screen, UiCommand.Click(submit.token))) { idle = 0; return@repeat }
                    searchedPersonal = false
                    if (navigation.openSearch(query)) { idle = 0; return@repeat }
                }
                // Public search may contain several exact or longer names; only a real observed collision prompts the user.
                val related = screen.nodes.filter { node -> !node.editable && node.label.length in 2..160 &&
                    PlaceText.normalize(node.label).startsWith(PlaceText.normalize(query)) &&
                    !Regex("검색|결과|등록").containsMatchIn(node.label) }.map(screen::clickTarget).distinctBy { it.token }
                if (searchAttempted && related.isNotEmpty() && cachedExact.isEmpty()) {
                    val node = chooseIdentity(screen, related, "검색 결과 중 어느 목적지인가요?")
                    if (node != null && act(screen, UiCommand.Click(node.token))) {
                        selectedName = node.label.ifBlank { screen.descendants(node.token).firstOrNull { it.label.isNotBlank() }?.label ?: query }
                        selectedAddress = screen.descendants(node.token).firstOrNull { it.label.contains(Regex("주소|[가-힣]+(?:로|길)\\s")) }?.label.orEmpty()
                        resolved = true; destinationObserved = true; idle = 0; return@repeat
                    }
                }
                if (assist(screen, false)) { idle = 0; return@repeat }
                if (++idle >= 4) throw UiUnavailable(InvocationState.LOW_CONFIDENCE,
                    "지도 앱의 집/회사·자주 가는 곳·저장 장소와 검색 화면에서 '$phrase' 목적지를 확정하지 못했습니다. 실제 장소명이나 주소를 알려주세요.")
                delay(300)
            }
            throw UiUnavailable(InvocationState.EXECUTION_FAILED, "지도 화면에서 작업 완료를 확인하지 못했습니다.")
        }
        // Cache only after observed guidance success. Cache failure must not reverse a completed goal.
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) {
            try {
                result.reference?.let(cache::save)
                val coordinates = result.coordinates
                if (coordinates != null && coordinates.supportedByNaver() && graph.settings.get("cache_ui_places") != "no") {
                    val id = slot ?: "ui_${PlaceText.normalize(phrase).hashCode().toUInt()}"
                    if (graph.places.get(id) == null) {
                        val now = System.currentTimeMillis()
                        graph.places.saveConfirmed(UserPlace(id, result.reference?.name ?: phrase,
                            ((slot?.let { PlaceSlots.aliases.getValue(it) } ?: emptyList()) + stripParticle(phrase)).distinct(),
                            coordinates, result.reference?.address.orEmpty(), "NAVER", PlaceSource.UI_VERIFIED, now, now))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional optimization only. */ }
        }
        return result
    }
    private suspend fun UiSession.chooseIdentity(screen: UiScreen, nodes: List<UiNode>, prompt: String): UiNode? {
        // Force a choice for untrusted private identities, including a single public result.
        return select(screen, nodes, prompt, forceChoice = true)
    }
    private fun stripParticle(value: String) = value.trim().let { text ->
        listOf("으로", "로", "에").firstOrNull { text.endsWith(it) && text.length > it.length }?.let { text.dropLast(it.length) } ?: text
    }
    private fun explicitCoordinates(screen: UiScreen): Coordinates? {
        val text = screen.text()
        val latitude = Regex("위도\\s*[:=]\\s*(\\d{2}\\.\\d+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val longitude = Regex("경도\\s*[:=]\\s*(\\d{3}\\.\\d+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        if (latitude != null && longitude != null) return runCatching { Coordinates(latitude, longitude) }.getOrNull()
        val link = Regex("nmap://(?:place|navigation)[^\\s]+").find(text)?.value ?: return null
        return runCatching {
            val uri = Uri.parse(link)
            Coordinates((uri.getQueryParameter("dlat") ?: uri.getQueryParameter("lat"))!!.toDouble(),
                (uri.getQueryParameter("dlng") ?: uri.getQueryParameter("lng"))!!.toDouble())
        }.getOrNull()
    }
}

/** Works from observed UI labels and optional local inference, rather than requiring an app-specific tool. */
class GenericUiGoal(private val graph: AgentApplication) {
    suspend fun execute(packageName: String, goal: String, media: MediaCommand? = null, launch: () -> Boolean): UiGoalResult = graph.uiAutomation.run(packageName, goal) {
        if (!launch()) throw UiUnavailable(InvocationState.EXECUTION_FAILED, "요청한 앱을 열지 못했습니다.")
        val label = SemanticUi.clickLabel(goal)
        val query = SemanticUi.searchQuery(goal)
        var original: UiScreen? = null
        var clicked: String? = null
        var searchSubmitted = false
        var idle = 0
        var mediaClicked = false
        var trackBefore = ""
        val history = mutableListOf<String>()
        repeat(18) {
            val screen = screen(); if (original == null) original = screen
            val changed = screen.text() != original?.text()
            if (media != null) {
                fun track(value: UiScreen) = value.nodes.filter { Regex("(?:song|track|now_playing).*title|title.*(?:song|track)|현재\\s*곡", RegexOption.IGNORE_CASE).containsMatchIn(it.viewId + it.label) }
                    .joinToString(" ") { it.label }
                if (mediaClicked && ((media == MediaCommand.RESUME && screen.exact("일시정지", "일시 정지", "Pause").isNotEmpty()) ||
                    (media == MediaCommand.PAUSE && screen.exact("재생", "Play").isNotEmpty()) ||
                    (media == MediaCommand.NEXT && trackBefore.isNotBlank() && track(screen).isNotBlank() && track(screen) != trackBefore)))
                    return@run UiGoalResult("요청한 음악 상태 변화를 앱 화면에서 확인했습니다.", "MEDIA_UI_STATE_CHANGED")
                val labels = when (media) {
                    MediaCommand.RESUME -> arrayOf("재생", "재생하기", "Play")
                    MediaCommand.PAUSE -> arrayOf("일시정지", "일시 정지", "Pause")
                    MediaCommand.NEXT -> arrayOf("다음 곡", "다음", "Next track", "Next")
                }
                val node = select(screen, screen.exact(*labels).map(screen::clickTarget).distinctBy { it.token }, "어느 음악 컨트롤인가요?")
                if (!mediaClicked && node != null) {
                    trackBefore = track(screen)
                    mediaClicked = act(screen, UiCommand.Click(node.token)); if (mediaClicked) return@repeat
                }
            }
            if (clicked != null && changed && label != null) return@run UiGoalResult("'$clicked' 선택 후 화면 변경을 확인했습니다.", "REQUESTED_CLICK_AND_SCREEN_CHANGED")
            if (query != null && searchSubmitted && changed && screen.nodes.any { !it.editable && it.label.contains(query) })
                return@run UiGoalResult("'$query' 검색 결과를 화면에서 확인했습니다.", "SEARCH_RESULTS_OBSERVED")
            if (label != null && clicked == null) {
                val nodes = SemanticUi.matches(screen, label)
                val node = select(screen, nodes, "같은 이름의 항목이 여러 개입니다. 어느 항목인가요?")
                if (node != null && act(screen, UiCommand.Click(node.token))) { clicked = label; idle = 0; return@repeat }
            }
            if (query != null && !searchSubmitted) {
                val input = screen.nodes.filter { it.editable }.let { values ->
                    values.singleOrNull() ?: values.singleOrNull { Regex("검색|search", RegexOption.IGNORE_CASE).containsMatchIn(it.label + it.viewId) }
                }
                if (input != null && act(screen, UiCommand.SetText(input.token, query))) {
                    val updated = screen(); val refreshed = updated.nodes.singleOrNull { it.token == input.token && it.editable }
                    val submit = updated.exact("검색", "Search").map(updated::clickTarget).firstOrNull()
                    if (submit != null && act(updated, UiCommand.Click(submit.token))) { searchSubmitted = true; return@repeat }
                    if (refreshed != null && act(updated, UiCommand.Submit(refreshed.token))) { searchSubmitted = true; return@repeat }
                }
                val search = screen.exact("검색", "Search").map(screen::clickTarget).firstOrNull()
                if (search != null && act(screen, UiCommand.Click(search.token))) return@repeat
            }
            when (val proposal = graph.planner.nextUi(goal, screen, history.takeLast(6))) {
                is UiProposal.Act -> if (act(screen, proposal.command)) { history += proposal.command.toString(); idle = 0; return@repeat }
                is UiProposal.Ambiguous -> {
                    val nodes = proposal.tokens.mapNotNull(screen::node)
                    val node = if (nodes.distinctBy { it.token }.size > 1) select(screen, nodes, proposal.prompt, forceChoice = true) else null
                    if (node != null && act(screen, UiCommand.Click(node.token))) { history += "USER_SELECTED:${node.label}"; idle = 0; return@repeat }
                }
                is UiProposal.Complete -> {
                    val evidence = screen.node(proposal.evidenceToken)?.label.orEmpty()
                    if (history.isNotEmpty() && changed && evidence.isNotBlank() && original?.nodes?.none { it.label == evidence } == true &&
                        Regex("완료|저장됨|저장되었습니다|추가됨|설정됨|검색 결과|result|saved|completed", RegexOption.IGNORE_CASE).containsMatchIn(evidence))
                        return@run UiGoalResult("앱 화면의 '$evidence' 완료 표시를 확인했습니다.", evidence)
                }
                UiProposal.Unavailable -> Unit
            }
            if (++idle >= 4) throw UiUnavailable(if (!graph.modelFile.isFile) InvocationState.MODEL_UNAVAILABLE else InvocationState.LOW_CONFIDENCE,
                "앱 화면의 자동 해결 경로에서 작업을 완료하지 못했습니다." +
                    if (!graph.modelFile.isFile) " 직접 버튼 선택·검색 외의 복잡한 목표 해석에는 로컬 모델이 필요합니다." else " 실제 화면의 작업 대상을 더 구체적으로 알려주세요.")
            delay(300)
        }
        throw UiUnavailable(InvocationState.EXECUTION_FAILED, "화면 작업 완료를 확인하지 못했습니다.")
    }
}
