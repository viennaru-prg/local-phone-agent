package dev.localphone.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class NavigationEvidence(val complete: Boolean, val state: String, val reason: String)

/** Per-command proof: destination selection → route preview → actual guidance, never ETA alone. */
class NavigationSession(val goal: String, private val names: List<String>) {
    private var initialSignature: Int? = null
    private var dispatched = false
    private var targetPicked = false
    private var destinationBound = false
    private var previewSeen = false
    private var startAttempts = 0
    private var lastStartAt = Long.MIN_VALUE / 2
    private var plainStartAt: Long? = null
    private val resolvedScreens = mutableSetOf<Int>()
    var evidence = NavigationEvidence(false, "UNRESOLVED", "목적지를 아직 확인하지 못함"); private set

    fun beforeDispatch(view: ScreenView?) { initialSignature = view?.signature; dispatched = true }

    private fun matches(label: String): Boolean = names.any { name ->
        Regex("(?:^|[\\s:：])${Regex.escape(name)}(?:$|[\\s,()])", RegexOption.IGNORE_CASE).containsMatchIn(label) ||
            GoalText.normalize(label) == GoalText.normalize(name)
    }

    fun isStart(label: String) = Regex("^(?:길|경로)?안내시작(?:하기|버튼|[0-9초후자동]*)?$")
        .matches(GoalText.normalize(label))

    private fun preview(view: ScreenView) = view.elements.any { isStart(it.label) }

    fun observe(view: ScreenView): NavigationEvidence {
        fun result(state: String, reason: String, done: Boolean = false): NavigationEvidence =
            NavigationEvidence(done, state, reason).also { evidence = it }
        if (view.snapshot.packageName != NAVER_MAP) return result("OTHER_APP", "네이버 지도 화면이 아님")
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
        if (preview(view)) {
            // An already open unrelated route must not become proof just because a deep link launched.
            if (targetPicked || (targetVisible && (!dispatched || changedAfterDispatch))) destinationBound = true
            if (destinationBound) previewSeen = true
            return result("PREVIEW", if (destinationBound) "목적지 확인됨; 안내 시작 전" else "경로는 보이지만 목적지 확인이 필요함")
        }
        val active = labels.any {
            Regex("(?:길|경로)?안내\\s*종료|경로\\s*안내\\s*중|주행\\s*중|경로\\s*안내를\\s*시작합니다").containsMatchIn(it)
        }
        if (!active) return result("UNCONFIRMED", "실제 안내 화면 증거 없음 (도착 예정·남은 거리만으로 완료하지 않음)")
        if (targetVisible && (!dispatched || changedAfterDispatch)) destinationBound = true
        if (!destinationBound || (!previewSeen && !targetVisible))
            return result("ACTIVE_UNBOUND", "안내 중이지만 이번 명령의 목적지인지 확인되지 않음")
        if (dispatched && !changedAfterDispatch && !previewSeen)
            return result("STALE_GUIDANCE", "명령 이전 안내 화면과 같아 새 경로 시작이 확인되지 않음")
        return result("GUIDANCE_VERIFIED", "요청 목적지와 실제 안내 화면을 확인함", true)
    }

    fun nextAction(view: ScreenView, now: Long): Harness.Auto? {
        if (view.snapshot.packageName != NAVER_MAP) return null
        if (!preview(view)) {
            if (evidence.state in setOf("PROMPT", "ACTIVE_UNBOUND", "WRONG_DESTINATION", "GUIDANCE_VERIFIED", "STALE_GUIDANCE")) return null
            if (view.signature in resolvedScreens) return null
            val hits = view.elements.filter { e -> e.enabled && e.kind in setOf(Kind.ITEM, Kind.BUTTON) && matches(e.label) &&
                !Regex("등록|수정|삭제|변경|설정").containsMatchIn(e.label) }
            val target = hits.filter { e -> names.any { GoalText.normalize(e.label) == GoalText.normalize(it) } }.singleOrNull()
                ?: hits.singleOrNull()
            if (target != null) return Harness.Auto(AgentAction.Click(target.id), "현재 지도에서 요청한 목적지 항목이 하나로 확인됨")
            // NAVER's home/work and frequent destinations live in route selection, not only Favorites.
            if (hits.isEmpty() && !targetPicked) view.elements.singleOrNull {
                it.enabled && it.kind != Kind.TEXT && GoalText.normalize(it.label) == "길찾기"
            }?.let { return Harness.Auto(AgentAction.Click(it.id), "길찾기의 집·회사·자주 가는 곳을 먼저 확인") }
            return null
        }
        if (!destinationBound) return null
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
        if (view.snapshot.packageName != NAVER_MAP) return
        if (action is AgentAction.Click) {
            val label = view.element(action.id)?.label.orEmpty()
            if (matches(label) || GoalText.normalize(label) == "길찾기") resolvedScreens += view.signature
            if (accepted && matches(label)) targetPicked = true
            if (isStart(label)) { startAttempts++; lastStartAt = now }
        }
        if (accepted && action is AgentAction.Type && names.any { GoalText.normalize(it) == GoalText.normalize(action.text) } &&
            view.element(action.id)?.label?.contains("출발") != true) targetPicked = true
    }

    companion object {
        const val NAVER_MAP = "com.nhn.android.nmap"
        fun forGoal(goal: String, place: Place? = null): NavigationSession? {
            if (!Router.isNavigationGoal(goal) || !Router.usesNaver(goal)) return null
            val text = goal.trim().substringAfter("에서", goal.trim()).trim()
            val fromGoal = Regex("^(.+?)(?:으로|로|까지|에)\\s*(?:안내|가자|가\\s*줘|가요|데려|길)")
                .find(text)?.groupValues?.get(1)?.trim()
            val target = fromGoal ?: GoalText.targetWords(goal).firstOrNull { it !in setOf("안내", "길안내", "길찾기", "네이버", "지도", "네비", "내비") }
            val aliases = when (GoalText.normalize(target.orEmpty())) {
                "집", "우리집", "자택" -> listOf("집", "우리집", "자택")
                "회사", "우리회사", "직장", "사무실" -> listOf("회사", "우리 회사", "직장", "사무실")
                "본가", "부모님집" -> listOf("본가", "부모님집")
                else -> listOfNotNull(target)
            }
            return NavigationSession(goal, place?.names ?: aliases)
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
            val view = phone.observe()?.let(ScreenCompactor::compact)
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
