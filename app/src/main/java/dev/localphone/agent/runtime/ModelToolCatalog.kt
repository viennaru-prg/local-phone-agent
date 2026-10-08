package dev.localphone.agent.runtime

import com.google.gson.Gson
import dev.localphone.core.*

/** Both adapters receive the same schema catalog. Screen tokens are scoped to one observation. */
object ModelToolCatalog {
    const val SYSTEM = "Interpret the smartphone goal and call provided functions. Include every requested action in one calls array. Normalize a clear place alias, preserve other Korean names and the original task. Never invent apps, URLs, coordinates or times. Functions only propose actions."
    // Keep all tools for both models, but fit the 1024-token Mobile Actions context.
    // Numeric/length/extra-property constraints remain enforced by the shared runtime decoder.
    private val descriptions = mapOf(
        "open_app" to "Launch an app (앱 열어/켜/실행). app_name is the named app. No navigation or destination.",
        "navigate" to "Start route guidance to a place (가자/가줘/네비/길안내). Opening a map app uses open_app.",
        "perform_app_task" to "App search/save/read (검색/저장/읽기), including notifications 알림. Copy the original goal. Unnamed app: empty app_name.",
        "set_alarm" to "Clock alarm (알람). Requires an explicit time. This does not read notifications (알림).",
        "set_timer" to "Countdown timer (타이머) for a duration in seconds.",
        "media_resume" to "Play or resume music (음악/노래 재생/틀어).",
        "media_pause" to "Pause music (음악/노래 멈춰/일시정지).",
        "media_next" to "Skip to the next song (다음 곡).")
    private val parameterHelp = mapOf("app_name" to "App explicitly named by user; otherwise empty for app tasks.", "goal" to "Original user command, unchanged.",
        "destination" to "Only the place name, without action words or suffixes.", "hour" to "24-hour clock hour.", "minute" to "Clock minute.",
        "seconds" to "Timer duration in seconds.", "page" to "Settings key from enum.")
    val phone: List<Map<String, Any>> = PhoneTools.registry.tools.map { registered ->
        mapOf("name" to registered.name, "description" to (descriptions[registered.name] ?: registered.description.take(120)),
            "parameters" to mapOf("type" to "object", "properties" to registered.properties.mapValues { (_, property) ->
                property.filterKeys { it == "type" || it == "enum" } }.mapValues { (name, property) ->
                property + mapOf("description" to parameterHelp.getValue(name)) }, "required" to registered.properties.keys.toList()))
    }
    private val node = mapOf<String, Any>("type" to "string", "minLength" to 1, "maxLength" to 100)
    private val text = mapOf<String, Any>("type" to "string", "maxLength" to 500)
    private fun schema(name: String, description: String, properties: Map<String, Any>) = mapOf<String, Any>(
        "name" to name, "description" to description,
        "parameters" to mapOf("type" to "object", "properties" to properties, "required" to properties.keys.toList(), "additionalProperties" to false))
    val ui = listOf(
        schema("ui_click", "Click one observed element for the user's goal.", mapOf("node" to node)),
        schema("ui_set_text", "Enter user-requested text into an observed editable element.", mapOf("node" to node, "text" to text)),
        schema("ui_submit", "Submit one observed editable element.", mapOf("node" to node)),
        schema("ui_scroll", "Scroll one observed scrollable element forward.", mapOf("node" to node)),
        schema("ui_back", "Return within the commanded app.", emptyMap()),
        schema("ui_complete", "Finish only with new observed completion evidence.", mapOf("node" to node)),
        schema("ui_choose", "Ask about actual ambiguous observed candidates.", mapOf("prompt" to text,
            "nodes" to mapOf("type" to "array", "items" to node, "minItems" to 1, "maxItems" to 8))),
    )
    data class Observation(val user: String, val tokens: Set<String>)
    fun observation(goal: String, screen: UiScreen, history: List<String>, limit: Int): Observation {
        val shown = (screen.nodes.filter { it.clickable || it.editable || it.scrollable } + screen.nodes.filter { it.label.isNotBlank() })
            .distinctBy { it.token }.take(limit)
        return Observation(Gson().toJson(mapOf("user_goal" to goal, "package" to screen.packageName,
            "nodes" to shown.map { mapOf("node" to it.token, "label" to it.label.take(100), "role" to it.role.substringAfterLast('.'),
                "clickable" to it.clickable, "editable" to it.editable, "scrollable" to it.scrollable) }, "history" to history.takeLast(3))), shown.map { it.token }.toSet())
    }
    fun decodeUi(calls: List<RawToolCall>, tokens: Set<String>): UiProposal {
        val call = calls.singleOrNull() ?: return UiProposal.Unavailable
        val args = call.arguments
        val token = args["node"] as? String
        if (token != null && token !in tokens) return UiProposal.Unavailable
        return when (call.name) {
            "ui_click" -> if (args.keys == setOf("node") && token != null) UiProposal.Act(UiCommand.Click(token)) else UiProposal.Unavailable
            "ui_set_text" -> if (args.keys == setOf("node", "text") && token != null && args["text"] is String)
                UiProposal.Act(UiCommand.SetText(token, args["text"] as String)) else UiProposal.Unavailable
            "ui_submit" -> if (args.keys == setOf("node") && token != null) UiProposal.Act(UiCommand.Submit(token)) else UiProposal.Unavailable
            "ui_scroll" -> if (args.keys == setOf("node") && token != null) UiProposal.Act(UiCommand.Scroll(token)) else UiProposal.Unavailable
            "ui_back" -> if (args.isEmpty()) UiProposal.Act(UiCommand.Back) else UiProposal.Unavailable
            "ui_complete" -> if (args.keys == setOf("node") && token != null) UiProposal.Complete(token) else UiProposal.Unavailable
            "ui_choose" -> {
                val values = (args["nodes"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                val prompt = args["prompt"] as? String
                if (args.keys == setOf("nodes", "prompt") && prompt != null && values.size in 1..8 && values.all { it in tokens })
                    UiProposal.Ambiguous(prompt.take(300), values.distinct()) else UiProposal.Unavailable
            }
            else -> UiProposal.Unavailable
        }
    }
}
