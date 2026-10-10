package dev.localphone.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class NavigationEvidence(val complete: Boolean, val state: String, val reason: String)

/** Per-command proof: destination selection → route preview → actual guidance, never ETA alone. */
/**
 * [app] says what differs in the navigation app (its package, the control that lists home/work/frequent
 * places, the controls shown only while guiding); the steps themselves are generic.
 */
class NavigationSession(val goal: String, private val names: List<String>, private val app: AppProfile = Harness.navigationApp(),
                        /** Other things the recognizer heard for the destination ("수요 모임" behind "수유 모임"). */
                        val heard: List<String> = emptyList(),
                        /** "회사까지 얼마나 걸려?": walk to the route preview and read it, never start guidance. */
                        val eta: Boolean = false) {
    /** What the assistant says once guidance is verified: "회사로 안내를 시작했어요." */
    val spokenStart: String get() = (chosenSpoken ?: answered.lastOrNull() ?: names.firstOrNull())?.takeIf { it.isNotBlank() }?.let { name ->
        val c = name.last()
        val batchim = c in '가'..'힣' && (c - '가') % 28 != 0 && (c - '가') % 28 != 8
        "$name${if (batchim) "으로" else "로"} 안내를 시작했어요."
    } ?: "길안내를 시작했어요."

    private var initialSignature: Int? = null
    private var dispatched = false
    private var targetPicked = false
    private var destinationBound = false
    private var previewSeen = false
    private var startAttempts = 0
    private var endSteps = 0
    private var etaWaits = 0
    private var carouselScrolls = 0
    private var carouselSignature: Int? = null
    private var carouselEnd = false
    private var lastStartAt = Long.MIN_VALUE / 2
    private var plainStartAt: Long? = null
    private val resolvedScreens = mutableSetOf<Int>()
    /** The route entry (길찾기) was opened: sideways lists from here on hold places. */
    private var targetSearchOpened = false
    /** Every place the carousel showed while paging (집, 회사, 수요모…, 일요모…, 수원집). */
    private val seenPlaces = linkedSetOf<String>()
    private var choiceAsked = false
    /** The saved place the model judged the user meant, when no name matched as heard. */
    private var chosen: String? = null
    private var chosenFull: String? = null
    var evidence = NavigationEvidence(false, "UNRESOLVED", "목적지를 아직 확인하지 못함"); private set

    fun beforeDispatch(view: ScreenView?) { initialSignature = view?.signature; dispatched = true }

    /**
     * No saved place matches the name as heard, the whole list was seen: the places for the model to
     * judge against (speech can mishear "수요모임" as "수유 모임"). Null when there is nothing to ask.
     */
    fun choiceNeeded(): List<String>? = seenPlaces.filter(::soundsNear).takeIf { carouselEnd && !choiceAsked && chosen == null && it.isNotEmpty() }

    /**
     * A saved place worth the model's judgment shares a sound with what was heard ("수요모…" for "수유
     * 모임"). With none ("이담한정식" against 집·회사·수원집) the model only guessed (it picked 수원집): the
     * name is looked up in the map's own search instead, which also knows the user's saved lists.
     */
    private fun soundsNear(place: String): Boolean {
        val said = (names + heard).flatMap { GoalText.normalize(it).filter { c -> c in '가'..'힣' }.toList() }.toSet()
        return GoalText.normalize(place.removeSuffix("…")).any { it in '가'..'힣' && it in said }
    }
    private var searchSteps = 0
    private var nameTyped = false
    /** The route button of a searched place was pressed; its 확인 sets it as the destination. */
    private var resultRouted = false
    private var resultConfirmed = false

    private var confirmed = false
    /** A name the user gave when answering "…로 안내할까요?" with another place ("아니, 수요모임"). */
    private val answered = mutableListOf<String>()

    /** The model's pick, said naturally: the preview's full name, else the heard name it starts with. */
    private val chosenSpoken: String? get() = chosen?.let { c -> chosenName ?: c.removeSuffix("…") }

    /**
     * The model's pick in full: the preview's name, a heard name it starts with, or a full name driven
     * to before ("수요모…" → 수요모임 remembered from "수요모임으로 안내해줘"). Null while only the cut label is known.
     */
    private val chosenName: String? get() = chosen?.let { c ->
        if (!c.endsWith("…")) return@let c
        val prefix = GoalText.normalize(c.removeSuffix("…"))
        chosenFull ?: (heard + names + AppProfiles.recall(app.packageName, "places")).firstOrNull {
            GoalText.normalize(it).let { n -> n.startsWith(prefix) && n.length > prefix.length }
        }
    }

    /**
     * A guessed destination is never driven to unasked: once its route preview shows (nothing started
     * yet), the user is asked "일요모임으로 안내할까요?". Null when there is nothing to confirm.
     */
    fun confirmQuestion(): String? {
        if (eta || chosen == null || confirmed || evidence.state != "PREVIEW" || !destinationBound) return null
        return "${withRo(chosenSpoken ?: return null)} 안내할까요?"
    }

    /** Full place names this run saw or used, for the recognizer's vocabulary (cut carousel labels are not names). */
    fun knownPlaceNames(): List<String> =
        (seenPlaces.filterNot { it.endsWith("…") } + listOfNotNull(chosenFull, chosenName) + answered +
            // The name as said, once it led to the place itself (guidance, or the route preview of a travel-time question).
            (if (evidence.complete || (destinationBound && chosen == null)) listOfNotNull(names.firstOrNull()) else emptyList())).distinct()

    val appPackage: String get() = app.packageName

    /** The route preview's travel time and distance, said for [eta] questions ("회사까지 차로 약 21분, 7.3km예요"). */
    fun etaAnswer(view: ScreenView): String? {
        if (!eta || evidence.state != "PREVIEW" || !destinationBound) return null
        val labels = view.elements.map { it.label }
        val time = labels.firstNotNullOfOrNull { Regex("(\\d+\\s*시간\\s*)?\\d+\\s*분").find(it)?.value }
            ?: labels.firstNotNullOfOrNull { Regex("\\d+\\s*시간").find(it)?.value } ?: return null
        // A place nearby reads in meters ("1분 350m").
        val distance = labels.firstNotNullOfOrNull { Regex("\\d+(?:[.,]\\d+)?\\s*(?:km|㎞|m)(?![a-zA-Z])").find(it)?.value }
        val place = chosenSpoken ?: answered.lastOrNull() ?: names.first()
        return "${place}까지 차로 약 ${time.replace(Regex("\\s+"), " ")}${distance?.let { ", $it" } ?: ""} 걸려요."
    }

    /** What to remember for the answer: the place that was asked about. */
    fun pendingPlace(): String? = chosen

    /** The user's answer to [confirmQuestion]: yes → go; another name → look for that one instead. */
    fun applyAnswer(place: String, answer: String) {
        val a = GoalText.normalize(answer)
        if (Regex("^(?:응|어|네|넵|예|그래|맞아|맞아요|좋아|ㅇㅇ|가자|가줘|안내해줘|부탁해|yes|ok)").containsMatchIn(a) &&
            !Regex("아니|말고|틀렸").containsMatchIn(a)) {
            chosen = place; confirmed = true; choiceAsked = true; targetPicked = true
            return
        }
        val other = answer.replace(Regex("^\\s*(?:아니|아니요|아뇨|아니야)[,\\s]*"), "")
            .replace(Regex("\\s*(?:말고|으로|로|에|가자|가줘|안내해\\s*줘|해\\s*줘)\\s*.*$"), "").trim()
        if (other.length >= 2) answered += other
        choiceAsked = true
    }

    private fun withRo(name: String): String {
        val c = name.last()
        val batchim = c in '가'..'힣' && (c - '가') % 28 != 0 && (c - '가') % 28 != 8
        return "$name${if (batchim) "으로" else "로"}"
    }

    /** The model's pick (null = none of them): page back through the list to it. */
    fun choose(place: String?) {
        choiceAsked = true
        if (place == null || !soundsNear(place)) return
        chosen = place
        // Collection ended at the list's start (paging back): look for the pick going forward again.
        carouselEnd = false; carouselScrolls = 0; carouselSignature = null; pageBack = !collectedBack
    }
    private var pageBack = false
    private var collectedBack = false

    /** Forget the progress of a route that was ended to start over (the map screens repeat). */
    private fun restart() {
        resolvedScreens.clear(); targetSearchOpened = false
        carouselEnd = false; carouselScrolls = 0; carouselSignature = null; pageBack = false; collectedBack = false
    }

    private fun matches(label: String): Boolean = (names + answered + listOfNotNull(chosen)).any { name ->
        val wanted = GoalText.normalize(name)
        Regex("(?:^|[\\s:：])${Regex.escape(name)}(?:$|[\\s,()])", RegexOption.IGNORE_CASE).containsMatchIn(label) ||
            GoalText.normalize(label) == wanted ||
            // "수원 집" spoken, "수원집 경기도 수원시 …" listed: the first words without spaces name the place.
            label.trim().split(Regex("\\s+")).let { words -> (1..minOf(3, words.size)).any { k -> GoalText.normalize(words.take(k).joinToString("")) == wanted } } ||
            // The route screen's carousel cuts long names: "수요모…" for 수요모임.
            Regex("^(.{2,})(?:…|\\.\\.\\.)$").matchEntire(label.trim())?.groupValues?.get(1)?.let { prefix ->
                GoalText.normalize(prefix).let { it.length >= 2 && wanted.startsWith(it) }
            } == true
    }

    fun isStart(label: String) = Regex("^(?:길|경로)?안내시작(?:하기|버튼|[0-9초후자동]*)?$")
        .matches(GoalText.normalize(label))

    private fun preview(view: ScreenView) = view.elements.any { isStart(it.label) }

    fun observe(view: ScreenView): NavigationEvidence {
        fun result(state: String, reason: String, done: Boolean = false): NavigationEvidence =
            NavigationEvidence(done, state, reason).also { evidence = it }
        if (view.snapshot.packageName != app.packageName) return result("OTHER_APP", "내비게이션 앱 화면이 아님")
        val labels = view.elements.map { it.label }
        if (Harness.authScreen(view) || labels.any {
            Regex("이어서.*(?:받으|안내)|이전.*(?:안내|경로)|경로.*하시겠|안내.*할까요").containsMatchIn(it)
        }) return result("PROMPT", "잠금·이전 경로·안내 확인 창이 남아 있음")
        val destination = view.elements.mapNotNull { e ->
            Regex("^(?:도착지|목적지)\\s*[:：]\\s*(.+)$").find(e.label)?.groupValues?.get(1)
                ?: e.value.takeIf { it.isNotBlank() && Regex("도착지|목적지").containsMatchIn(e.label) }
        }
        if (destination.isNotEmpty() && destination.none(::matches)) {
            destinationBound = false; targetPicked = false
            return result("WRONG_DESTINATION", "현재 목적지가 요청한 장소와 다름")
        }
        val targetVisible = labels.any(::matches) || destination.any(::matches)
        val changedAfterDispatch = initialSignature == null || initialSignature != view.signature
        // A cut carousel name the model chose ("수요모…"): the route preview shows it in full.
        chosen?.takeIf { it.endsWith("…") && chosenFull == null }?.let { cut ->
            val prefix = GoalText.normalize(cut.removeSuffix("…"))
            chosenFull = labels.mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull() }
                .firstOrNull { !it.endsWith("…") && GoalText.normalize(it).startsWith(prefix) && GoalText.normalize(it).length > prefix.length }
        }
        if (preview(view)) {
            // An already open unrelated route must not become proof just because a deep link launched.
            if (targetPicked || (targetVisible && (!dispatched || changedAfterDispatch))) destinationBound = true
            if (destinationBound) previewSeen = true
            return result("PREVIEW", if (destinationBound) "목적지 확인됨; 안내 시작 전" else "경로는 보이지만 목적지 확인이 필요함")
        }
        // A driving screen may show no "안내 중" text, only controls that exist while guiding (profile).
        val active = labels.any { app.activeGuidanceRegex().containsMatchIn(it) || drivingText.containsMatchIn(it) }
        if (!active) return result("UNCONFIRMED", "실제 안내 화면 증거 없음 (도착 예정·남은 거리만으로 완료하지 않음)")
        if (targetVisible && (!dispatched || changedAfterDispatch)) destinationBound = true
        if (!destinationBound || (!previewSeen && !targetVisible))
            return result("ACTIVE_UNBOUND", "안내 중이지만 이번 명령의 목적지인지 확인되지 않음")
        if (dispatched && !changedAfterDispatch && !previewSeen)
            return result("STALE_GUIDANCE", "명령 이전 안내 화면과 같아 새 경로 시작이 확인되지 않음")
        return result("GUIDANCE_VERIFIED", "요청 목적지와 실제 안내 화면을 확인함", true)
    }

    fun nextAction(view: ScreenView, now: Long): Harness.Auto? {
        if (view.snapshot.packageName != app.packageName) return null
        if (!preview(view)) {
            // Guidance from before this command (its destination is not shown as "회사"): end it from the
            // drawer and start the requested route the normal way. Never for the route this run started.
            if (evidence.state in setOf("ACTIVE_UNBOUND", "WRONG_DESTINATION", "STALE_GUIDANCE") && !targetPicked && !previewSeen && endSteps < 4) {
                Skills.pressBehindMenu(app.endGuidanceRegex(), view, emptyList(), "이번 목적지로 확인되지 않는 이전 안내를 끝내고 새로 시작")
                    ?.let { endSteps++; restart(); return it }
            }
            // "안내를 종료할까요?" after our own 안내 종료.
            if (evidence.state == "PROMPT" && endSteps > 0 && endSteps < 5) view.elements.firstOrNull {
                it.enabled && it.kind != Kind.TEXT && Regex("^(?:종료|확인|안내\\s*종료)$").matches(it.label.trim())
            }?.let { endSteps++; return Harness.Auto(AgentAction.Click(it.id), "안내 종료 확인") }
            if (evidence.state in setOf("PROMPT", "ACTIVE_UNBOUND", "WRONG_DESTINATION", "GUIDANCE_VERIFIED", "STALE_GUIDANCE")) return null
            val named = view.elements.filter { e -> e.enabled && e.kind in setOf(Kind.ITEM, Kind.BUTTON) && matches(e.label) &&
                !Regex("등록|수정|삭제|변경|설정").containsMatchIn(e.label) }
            // A search box at the top echoes the name ("이담한정식"); with a result below it, the result is the place.
            fun echo(e: Element) = e.bounds.bottom <= view.snapshot.height * 0.15 && names.any { GoalText.normalize(e.label) == GoalText.normalize(it) }
            val hits = named.filterNot(::echo).takeIf { below -> below.isNotEmpty() && below.size < named.size } ?: named
            // A searched place set as the destination asks for confirmation (NAVER: the place, its address, 확인).
            if (resultRouted && !resultConfirmed) view.elements.singleOrNull { it.enabled && it.kind != Kind.TEXT && it.label.trim() == "확인" }
                ?.takeIf { view.elements.any { e -> matches(e.label) } }
                ?.let { targetPicked = true; resultConfirmed = true; return Harness.Auto(AgentAction.Click(it.id), "검색한 장소를 도착지로 확인") }
            // The place was picked for a travel-time question and the route preview is still drawing.
            if (eta && targetPicked && hits.isEmpty() && etaWaits < 8) { etaWaits++; return Harness.Auto(AgentAction.Wait, "경로 미리보기가 뜨기를 기다림") }
            if (view.signature in resolvedScreens) return null
            val target = hits.filter { e -> names.any { GoalText.normalize(e.label) == GoalText.normalize(it) } }.singleOrNull()
                ?: hits.singleOrNull()
            // A search result row ("이담한정식 4.3km 한정식 …") carries its own route button: that sets it as
            // the destination, where pressing the row only opens the place's page.
            if (target != null && !resultRouted && names.none { GoalText.normalize(target.label) == GoalText.normalize(it) }) view.elements
                .singleOrNull { it.enabled && it.kind == Kind.BUTTON && GoalText.normalize(it.label) in setOf("길찾기", "도착") }
                ?.let { resultRouted = true; return Harness.Auto(AgentAction.Click(it.id), "검색 결과 '${target.label.take(20)}'을 도착지로 길찾기") }
            if (target != null) return Harness.Auto(AgentAction.Click(target.id),
                if (chosen != null) "모델이 고른 저장 장소 '${target.label}'" else "현재 지도에서 요청한 목적지 항목이 하나로 확인됨")
            // Home/work and frequent destinations are listed behind the app's route entry (NAVER: 길찾기):
            // open it first. The map's own sideways lists (category chips) are not places.
            if (hits.isEmpty() && !targetPicked) app.routeEntry?.let { entry -> view.elements.singleOrNull {
                it.enabled && it.kind != Kind.TEXT && GoalText.normalize(it.label) == GoalText.normalize(entry)
            } }?.let { return Harness.Auto(AgentAction.Click(it.id), "${it.label}의 집·회사·자주 가는 곳을 먼저 확인") }
            // A sideways list of places (NAVER: 집, 회사, 수요모…, 일요모…) shows a few at a time: page through it.
            if (hits.isEmpty() && !targetPicked) placeCarousel(view)?.let { carousel ->
                // Remember what the list offers, for the model to judge if nothing matches as heard.
                view.elements.filter { e -> e.enabled && e.kind != Kind.TEXT && e.kind != Kind.LIST &&
                    e.bounds.centerY in carousel.bounds.top..carousel.bounds.bottom && e.bounds.centerX in carousel.bounds.left..carousel.bounds.right &&
                    !Regex("^(?:더보기|전체\\s*보기|등록|편집|추가|more|edit|add)$", RegexOption.IGNORE_CASE).matches(e.label.trim())
                }.forEach { seenPlaces += it.label.trim() }
                if (carouselSignature == view.signature) carouselEnd = true // the last scroll moved nothing
                // Both ends seen: the whole list, for the next command to skip paging through it.
                if (carouselEnd && collectedBack && !choiceAsked) AppProfiles.learn(app.packageName, "frequents", seenPlaces.joinToString("|"))
                // The list as seen last time holds nothing named or sounding like this: look the name up
                // right away instead of paging both ways again (3-4 s). A place added since is found by the search.
                val known = AppProfiles.recall(app.packageName, "frequents")
                if (carouselScrolls == 0 && !carouselEnd && chosen == null && !choiceAsked && known.isNotEmpty() &&
                    (known + seenPlaces).none { matches(it) || soundsNear(it) }) {
                    carouselEnd = true; collectedBack = true; choiceAsked = true
                }
                // The list may have been left scrolled to its end: before asking, walk back to its start too,
                // so the model sees every place (집 and 회사 were missing once).
                if (carouselEnd && chosen == null && !choiceAsked && !collectedBack) {
                    collectedBack = true; pageBack = true; carouselEnd = false; carouselScrolls = 0; carouselSignature = null
                }
                if (!carouselEnd && carouselScrolls < 8) {
                    carouselScrolls++; carouselSignature = view.signature
                    val dir = if (pageBack) ScrollDir.LEFT else ScrollDir.RIGHT
                    return Harness.Auto(AgentAction.Scroll(dir, carousel.id), "자주 가는 곳 목록을 넘겨 '${chosen ?: names.first()}' 찾기")
                }
                // Every place was seen and none sounds like the name: nothing for the model to judge.
                if (carouselEnd && chosen == null && !choiceAsked && seenPlaces.none(::soundsNear)) choiceAsked = true
            }
            // Not among the frequent places: type the name as the destination ("저장" lists and any place
            // by name). The result named so is then pressed like a frequent place.
            if (choiceAsked && chosen == null && !targetPicked && searchSteps < 4) {
                val input = view.inputs.firstOrNull { it.enabled }
                if (input != null && !nameTyped && targetSearchOpened) {
                    nameTyped = true; searchSteps++
                    return Harness.Auto(AgentAction.Type(input.id, names.first(), true), "자주 가는 곳에 없는 '${names.first()}'을 도착지로 검색")
                }
                if (!nameTyped) view.elements.firstOrNull { e ->
                    e.enabled && e.kind != Kind.TEXT && Regex("^(?:도착지|목적지)\\s*(?:입력|검색)?$").matches(e.label.trim())
                }?.let { searchSteps++; return Harness.Auto(AgentAction.Click(it.id), "도착지 입력란을 열어 '${names.first()}' 찾기") }
            }
            return null
        }
        if (!destinationBound) return null
        // A travel-time question stops at the preview (etaAnswer). The route list draws a moment after the
        // preview itself: wait for its time instead of handing the half-drawn screen to the model.
        if (eta) return if (etaAnswer(view) == null && etaWaits++ < 8) Harness.Auto(AgentAction.Wait, "경로 시간이 표시되기를 기다림") else null
        // A destination the model guessed waits here, on the preview, for the user's yes (confirmQuestion).
        if (chosen != null && !confirmed) return null
        val starts = view.elements.filter { isStart(it.label) }
        // NAVER sometimes exposes the visual button only as TextView. A computed, bound route and
        // a stable enabled label allow an observed-node gesture, never a guessed screen coordinate.
        val routeReady = view.elements.any { Regex("[0-9]+\\s*(?:분|km|㎞)|자동차").containsMatchIn(it.label) } &&
            view.elements.none { Regex("경로.*(?:계산|탐색)\\s*중").containsMatchIn(it.label) }
        if (plainStartAt == null && starts.any { it.enabled }) plainStartAt = now
        val ready = starts.firstOrNull { it.enabled && (it.kind != Kind.TEXT ||
            (routeReady && now - (plainStartAt ?: now) >= 500)) }
        if (ready == null) return Harness.Auto(AgentAction.Wait, "안내 시작 버튼이 활성화될 때까지 기다림")
        if (startAttempts >= 3) return null
        if (now - lastStartAt < 750) return Harness.Auto(AgentAction.Wait, "안내 시작 후 화면 반응 확인 중")
        return Harness.Auto(AgentAction.Click(ready.id), if (startAttempts == 0) "현재 안내 시작 버튼 클릭" else "클릭 후 전환되지 않아 현재 버튼을 다시 읽고 탭")
    }

    fun needsTap(view: ScreenView, id: Int) = view.element(id)?.let {
        isStart(it.label) && (startAttempts > 0 || it.kind == Kind.TEXT)
    } == true

    fun recordAction(view: ScreenView, action: AgentAction, accepted: Boolean, now: Long) {
        if (view.snapshot.packageName != app.packageName) return
        if (action is AgentAction.Click) {
            val label = view.element(action.id)?.label.orEmpty()
            val entry = app.routeEntry?.let { GoalText.normalize(label) == GoalText.normalize(it) } == true
            if (matches(label) || entry) resolvedScreens += view.signature
            if (accepted && entry) targetSearchOpened = true
            if (accepted && matches(label)) targetPicked = true
            if (isStart(label)) { startAttempts++; lastStartAt = now }
        }
        if (accepted && action is AgentAction.Type && names.any { GoalText.normalize(it) == GoalText.normalize(action.text) } &&
            view.element(action.id)?.label?.contains("출발") != true) targetPicked = true
    }

    /** A sideways list holding clickable places: NAVER names it "v frequents recycler view"; any wide, short list counts. */
    private fun placeCarousel(view: ScreenView): Element? = view.lists.firstOrNull { l ->
        Regex("frequent|favorite|즐겨", RegexOption.IGNORE_CASE).containsMatchIn(l.label)
    } ?: view.lists.firstOrNull { l ->
        // By shape only after the route entry was opened (the map screen's category chips are sideways too).
        targetSearchOpened && (l.bounds.width >= l.bounds.height * 3 && view.elements.count { e ->
                e.kind != Kind.TEXT && e.kind != Kind.LIST && e.bounds.centerY in l.bounds.top..l.bounds.bottom &&
                    e.bounds.centerX in l.bounds.left..l.bounds.right
            } >= 2)
    }

    companion object {
        private val drivingText = Regex("경로\\s*안내\\s*중|주행\\s*중|경로\\s*안내를\\s*시작합니다")
        const val NAVER_MAP = "com.nhn.android.nmap"
        /** "회사까지 얼마나 걸려?": the same walk to the place, stopping at its route preview. */
        fun forEta(goal: String, alternatives: List<String> = emptyList()): NavigationSession? {
            val target = QuickCommands.etaTarget(goal) ?: return null
            val names = when (GoalText.normalize(target)) {
                "집", "우리집", "자택" -> listOf("집", "우리집", "자택")
                "회사", "우리회사", "직장", "사무실" -> listOf("회사", "우리 회사", "직장", "사무실")
                else -> listOf(target)
            }
            val heard = alternatives.mapNotNull { QuickCommands.etaTarget(it) }.filter { GoalText.normalize(it) != GoalText.normalize(target) }.distinct()
            return NavigationSession(goal, names, heard = heard, eta = true)
        }

        /** [alternatives]: the recognizer's other hypotheses for the whole command ("수요 모임으로 안내해 줘"). */
        fun forGoal(goal: String, place: Place? = null, alternatives: List<String> = emptyList()): NavigationSession? {
            if (!Router.isNavigationGoal(goal) || !Router.usesNaver(goal)) return null
            val target = ShortcutGoals.navigationTarget(goal) ?: return null
            val aliases = when (GoalText.normalize(target.orEmpty())) {
                "집", "우리집", "자택" -> listOf("집", "우리집", "자택")
                "회사", "우리회사", "직장", "사무실" -> listOf("회사", "우리 회사", "직장", "사무실")
                "본가", "부모님집" -> listOf("본가", "부모님집")
                else -> listOfNotNull(target)
            }
            val heard = alternatives.mapNotNull { ShortcutGoals.navigationTarget(it) }.filter { GoalText.normalize(it) != GoalText.normalize(target) }.distinct()
            return NavigationSession(goal, place?.takeIf { p -> p.names.any { GoalText.normalize(it) == GoalText.normalize(target) } }?.names ?: aliases,
                heard = heard)
        }
    }
}

/** Bounded direct-tool observation; a timeout returns false so the generic screen agent takes over. */
class NavigationDriver(private val phone: Phone, private val session: NavigationSession,
                       private val listener: AgentListener = object : AgentListener {}) {
    suspend fun confirm(timeoutMs: Long = 6000): Boolean {
        val started = phone.now()
        var lastState = ""
        while (phone.now() - started < timeoutMs) {
            currentCoroutineContext().ensureActive()
            val view = phone.observe()?.let { ScreenCompactor.compact(it, session.goal) }
            if (view == null) { delay(150); continue }
            val evidence = session.observe(view)
            if (lastState != evidence.state) {
                listener.step(StepRecord(0, "navigation", view.render(), "", "observe", evidence.state, evidence.reason, 0, phone.now() - started))
                lastState = evidence.state
            }
            if (evidence.complete) return true
            if (view.snapshot.packageName == NavigationSession.NAVER_MAP) {
                val auto = session.nextAction(view, phone.now()) ?: Harness.preDecide(session.goal, view)
                if (auto?.action is AgentAction.Click && Guard.blocked(session.goal, auto.action, view) == null) {
                    val click = auto.action as AgentAction.Click
                    val tapped = session.needsTap(view, click.id)
                    val ok = if (tapped) phone.tap(view, click.id) else phone.perform(view, click)
                    session.recordAction(view, click, ok, phone.now())
                    listener.step(StepRecord(0, "navigation", view.render(), "", click.describe(view),
                        if (ok) "클릭 전달됨; 안내 화면 검증 대기" else "클릭 전달 실패", auto.reason, 0, phone.now() - started))
                }
            }
            delay(150)
        }
        listener.step(StepRecord(0, "navigation", "", "", "fallback", "미확인", session.evidence.reason, 0, phone.now() - started))
        return false
    }
}
