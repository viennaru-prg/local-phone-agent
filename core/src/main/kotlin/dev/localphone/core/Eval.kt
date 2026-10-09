package dev.localphone.core

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Offline decision check: a screen (as the model would see it), a goal, and the acceptable actions.
 * Runs on the phone with the real model but never touches other apps.
 */
data class EvalCase(
    val name: String,
    val goal: String,
    val app: String,
    /** Each element: [kind word, label] or [kind word, label, "on"/"off"/"selected"], e.g. ["항목", "회사"]. */
    val elements: List<List<String>>,
    /** Accepted actions: "click:회사", "open_app:지도", "type:고양이", "media:play", "done", "fail", "back", "ask", "scroll". */
    val expect: List<String>,
    val history: List<List<String>>? = null, // nullable: Gson leaves missing fields null
)

data class EvalResult(val case: EvalCase, val pass: Boolean, val got: String, val raw: String, val ms: Long)

object Evaluator {
    fun load(json: String): List<EvalCase> = Gson().fromJson(json, object : TypeToken<List<EvalCase>>() {}.type)

    fun view(case: EvalCase): ScreenView {
        val nodes = mutableListOf(RawNode("r", -1, bounds = Bounds(0, 0, 1080, 2400)))
        case.elements.forEachIndexed { i, e ->
            val kind = Kind.entries.firstOrNull { it.word == e[0] } ?: Kind.ITEM
            val state = e.getOrNull(2).orEmpty()
            val top = 400 + i * 60 // below the "상단" zone so synthetic screens read like the middle of a page
            val value = state.removePrefix("value=").takeIf { state.startsWith("value=") }.orEmpty()
            nodes += RawNode("r.$i", 0,
                text = if (kind == Kind.INPUT) value else e[1], hint = if (kind == Kind.INPUT) e[1] else "",
                className = if (kind == Kind.BUTTON) "android.widget.Button" else "android.view.View",
                clickable = kind in setOf(Kind.BUTTON, Kind.ITEM), editable = kind == Kind.INPUT, scrollable = kind == Kind.LIST,
                checkable = kind == Kind.SWITCH, checked = state == "on", selected = state == "selected",
                bounds = Bounds(0, top, 1080, top + 56))
        }
        return ScreenCompactor.compact(Snapshot("eval", case.app, nodes, 1080, 2400, home = case.app.contains("홈")))
    }

    fun history(case: EvalCase) = case.history.orEmpty().map { HistoryLine(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }

    fun judge(case: EvalCase, view: ScreenView, action: AgentAction): Boolean = case.expect.any { want ->
        val (kind, arg) = want.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        fun same(a: String, b: String) = GoalText.normalize(a) == GoalText.normalize(b)
        when (kind) {
            "click" -> action is AgentAction.Click && view.element(action.id)?.label?.let { same(it, arg) } == true
            "open_app" -> action is AgentAction.OpenApp && GoalText.normalize(action.app).contains(GoalText.normalize(arg))
            "type" -> action is AgentAction.Type && GoalText.normalize(action.text).contains(GoalText.normalize(arg))
            "media" -> action is AgentAction.Media && action.key.name.equals(arg, true)
            "done" -> action is AgentAction.Done
            "fail" -> action is AgentAction.Fail
            "ask" -> action is AgentAction.Ask
            "back" -> action == AgentAction.Back
            "scroll" -> action is AgentAction.Scroll
            else -> false
        }
    }
}
