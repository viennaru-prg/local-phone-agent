package dev.localphone.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

/** NAVER startup ad dialogs. Ordinary inline banners are not navigation blockers. */
object NavigationPopup {
    data class Popup(val container: Int, val closeNode: Int?)
    private val ad = Regex("(?:^|\\s|\\[)(?:광고|advertisement|sponsored|ad)(?:$|\\s|\\])", RegexOption.IGNORE_CASE)
    private val close = Regex("^(?:광고\\s*|팝업\\s*)?닫기$|^(?:close(?:\\s*ad)?|dismiss|skip\\s*ad)$", RegexOption.IGNORE_CASE)
    private val crosses = setOf("X", "x", "×", "✕", "✖", "삭제", "delete")
    private val adRequest = Regex("광고.{0,16}(?:정보|신고|제보|보여|보기|열어|눌러|클릭)",RegexOption.IGNORE_CASE)
    private val adWords = setOf("ad", "ads", "advert", "advertisement", "interstitial")
    private val popupWords = setOf("popup", "dialog", "interstitial", "modal")
    private val camel = Regex("([a-z])([A-Z])")
    private val separators = Regex("[^a-z0-9가-힣]+")
    private val mapLabel = Regex("^(?:길찾기|도착지 입력|현위치|내위치|네이버지도 검색|현재 지도 중심)$|^(?:안내\\s*시작|자동차\\s|내위치[:：])")
    private fun words(id: String) = id.substringAfterLast('/').replace(camel, "$1 $2").lowercase().split(separators)

    fun contains(snapshot: Snapshot, container: Int, node: Int): Boolean {
        var index = node
        repeat(45) {
            if (index == container) return true
            index = snapshot.nodes.getOrNull(index)?.parent ?: return false
        }
        return false
    }

    fun find(snapshot: Snapshot, goal: String = ""): Popup? {
        if (adRequest.containsMatchIn(goal)) return null
        if (snapshot.packageName != NavigationSession.NAVER_MAP || snapshot.width <= 0 || snapshot.height <= 0) return null
        val nodes = snapshot.nodes
        if (nodes.isEmpty()) return null
        val strong = BooleanArray(nodes.size)
        val ads = BooleanArray(nodes.size)
        nodes.forEachIndexed { i, n ->
            val w = words(n.viewId)
            strong[i] = w.any { it in adWords } && w.any { it in popupWords }
            ads[i] = w.any { it in adWords } || ad.containsMatchIn(n.ownLabel)
        }
        for (i in nodes.indices.reversed()) if (nodes[i].parent in nodes.indices && ads[i]) ads[nodes[i].parent] = true
        if (ads.none { it }) return null
        val mapControls = nodes.any { it.editable || mapLabel.containsMatchIn(it.ownLabel.trim()) }
        fun inList(i: Int): Boolean {
            var parent = nodes[i].parent
            repeat(45) { if (parent !in nodes.indices) return false; if (nodes[parent].scrollable) return true; parent = nodes[parent].parent }
            return true
        }
        val panels = nodes.indices.filter { i ->
            val n = nodes[i]; val b = n.bounds
            if (!ads[i] || b.empty || b.right <= 0 || b.bottom <= 0 || b.left >= snapshot.width || b.top >= snapshot.height) false
            else strong[i] || n.className.endsWith("Dialog") || n.className.endsWith("PopupWindow") ||
                (b.width >= snapshot.width * .55 && b.height >= snapshot.height * .20 &&
                    b.top >= snapshot.height * .06 && b.bottom <= snapshot.height * .94 &&
                    abs(b.centerX - snapshot.width / 2) <= snapshot.width * .16 &&
                    !n.clickable && !n.scrollable && !inList(i) && !n.className.endsWith("ImageView")) ||
                (i == 0 && !mapControls && nodes.any { c -> close.matches(c.ownLabel.trim()) || c.ownLabel.trim() in crosses })
        }.sortedBy { nodes[it].bounds.width.toLong() * nodes[it].bounds.height }
        var unresolved: Popup? = null
        for (panel in panels) {
            val box = nodes[panel].bounds
            fun corner(n: RawNode) = n.bounds.width <= snapshot.width * .25 && n.bounds.height <= snapshot.height * .09 &&
                n.bounds.centerX >= box.left + box.width * .72 && n.bounds.centerY <= box.top + box.height * .18
            fun interactive(n: RawNode) = n.clickable || n.className.endsWith("Button")
            val inside = nodes.indices.filter { i ->
                val n = nodes[i]; val b = n.bounds
                i != panel && contains(snapshot, panel, i) && !b.empty &&
                    b.right > 0 && b.bottom > 0 && b.left < snapshot.width && b.top < snapshot.height && interactive(n)
            }
            val named = inside.filter { i ->
                val n = nodes[i]; val label = n.ownLabel.trim().substringBefore(" · ")
                val w = words(n.viewId)
                close.matches(label) || (w.any { it in setOf("close", "dismiss") } && n.bounds.height <= snapshot.height * .09) ||
                    (label in crosses && corner(n))
            }
            val distinctNamed = named.sortedByDescending { nodes[it].className.endsWith("Button") }.distinctBy { nodes[it].bounds }
            val anonymous = inside.filter { i ->
                val n = nodes[i]
                n.ownLabel.isBlank() && n.viewId.isBlank() && corner(n) &&
                    (n.className.endsWith("Button") || n.className.endsWith("ImageView"))
            }
            val node = distinctNamed.singleOrNull() ?: anonymous.singleOrNull().takeIf { distinctNamed.isEmpty() }
            if (node != null) return Popup(panel,node)
            if (unresolved == null || strong[panel]) unresolved = Popup(panel,null)
        }
        return unresolved
    }

    fun isClose(view: ScreenView, id: Int) = find(view.snapshot)?.closeNode?.let { it == view.element(id)?.node } == true
    fun action(view: ScreenView,goal: String = ""): Harness.Auto? {
        val popup = find(view.snapshot,goal) ?: return null
        val button = view.elements.firstOrNull { it.node == popup.closeNode && it.enabled }
        return if (button != null) Harness.Auto(AgentAction.Click(button.id), "지도 시작 광고 팝업의 닫기 버튼")
            else Harness.Auto(AgentAction.Wait, "광고 닫기 버튼이 나타나기를 기다림")
    }
}

/** Shared by a direct-link driver and its screen-agent continuation. No idle polling. */
class PopupDismissState {
    var attempts = 0; internal set
    var blockedReason: String? = null; internal set
}

class NavigationPopupHandler(private val phone: Phone, private val goal: String,
    private val listener: AgentListener, private val state: PopupDismissState) {
    sealed interface Result {
        data object None : Result
        data class Cleared(val view: ScreenView, val history: List<HistoryLine>) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun handle(initial: ScreenView, budgetMs: Long = 3_000): Result {
        if (NavigationPopup.find(initial.snapshot,goal) == null) { state.blockedReason = null; return Result.None }
        state.blockedReason?.let { return Result.Failed(it) }
        listener.progress("광고 팝업 닫는 중…")
        val start = phone.now()
        var view = initial
        var clicks = 0
        var retryAt = start
        val history = mutableListOf<HistoryLine>()
        while (phone.now() - start < budgetMs) {
            currentCoroutineContext().ensureActive()
            val popup = NavigationPopup.find(view.snapshot,goal)
            if (popup == null) {
                if (view.snapshot.packageName != NavigationSession.NAVER_MAP) return failed("광고를 닫은 뒤 지도 화면을 확인하지 못했어요.")
                listener.step(StepRecord(0,"popup",view.render(),"","popup_verify","닫힘 확인","원래 안내를 계속함",0,phone.now()-start))
                history += HistoryLine("광고 팝업 확인", "닫힘 확인")
                return Result.Cleared(view,history)
            }
            val button = view.elements.firstOrNull { it.node == popup.closeNode && it.enabled }
            if (button != null && phone.now() >= retryAt && clicks < 2 && state.attempts < 4) {
                val click = AgentAction.Click(button.id)
                if (Guard.blocked(goal,click,view) == null) {
                    val retry = clicks > 0
                    clicks++; state.attempts++
                    val ok = if (retry) phone.tap(view,button.id) else phone.perform(view,click) || phone.tap(view,button.id)
                    history += HistoryLine("광고 팝업 닫기",if(ok) "클릭 전달; 닫힘 확인 대기" else "클릭 전달 실패")
                    listener.step(StepRecord(0,"popup",view.render(),"","popup_close",
                        history.last().outcome,"목적지 선택과 별도로 처리",0,phone.now()-start))
                    retryAt = phone.now() + if(ok) 900 else 300
                }
            }
            if (phone.now() >= retryAt && (clicks >= 2 || state.attempts >= 4)) break
            delay(minOf(150,budgetMs-(phone.now()-start)).coerceAtLeast(1))
            phone.observe()?.let { view = ScreenCompactor.compact(it,goal) }
        }
        return failed("광고 팝업을 닫았는지 확인하지 못했어요. 광고를 닫은 뒤 다시 말씀해 주세요.")
    }

    private fun failed(message: String): Result.Failed {
        state.blockedReason = message
        listener.step(StepRecord(0,"popup","","","popup_blocked","미확인",message,0,0))
        return Result.Failed(message)
    }
}
