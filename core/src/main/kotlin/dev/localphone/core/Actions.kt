package dev.localphone.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class MediaKey { PLAY, PAUSE, NEXT, PREVIOUS }
enum class ScrollDir { DOWN, UP, LEFT, RIGHT }

sealed interface AgentAction {
    data class OpenApp(val app: String) : AgentAction
    data class Click(val id: Int) : AgentAction
    data class LongClick(val id: Int) : AgentAction
    /** Harness only: tap the icon at the right end of a row (ClipStream's delete icon has no node of its own). */
    data class TapEnd(val id: Int) : AgentAction
    /** Harness only: two real taps (ClipStream's playlist row: the first selects, the second plays). */
    data class DoubleTap(val id: Int) : AgentAction
    data class Type(val id: Int, val text: String, val enter: Boolean) : AgentAction
    data class Scroll(val dir: ScrollDir, val id: Int?) : AgentAction
    data class Inspect(val query: String) : AgentAction
    data object Back : AgentAction
    data object Wait : AgentAction
    data class Media(val key: MediaKey) : AgentAction
    data class Done(val say: String) : AgentAction
    data class Ask(val question: String) : AgentAction
    data class Fail(val reason: String) : AgentAction
}

data class Decision(val note: String, val action: AgentAction, val expect: String = "", val check: Boolean = false)

class BadModelOutput(message: String) : Exception(message)

object ActionParser {
    fun parse(raw: String, view: ScreenView): Decision {
        val json = runCatching { JsonParser.parseString(raw.trim()).asJsonObject }
            .getOrElse { throw BadModelOutput("JSON 아님: ${raw.take(120)}") }
        val note = json.str("note")
        val action = when (val name = json.str("action")) {
            "open_app" -> AgentAction.OpenApp(json.str("app").ifBlank { throw BadModelOutput("앱 이름 없음") })
            "click" -> AgentAction.Click(json.id(view))
            "long_click" -> AgentAction.LongClick(json.id(view))
            "type" -> {
                val id = json.id(view)
                if (view.element(id)?.kind != Kind.INPUT) throw BadModelOutput("[$id]은 입력칸이 아님")
                AgentAction.Type(id, json.str("text"), json.get("enter")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)
            }
            "scroll" -> AgentAction.Scroll(runCatching { ScrollDir.valueOf(json.str("dir").uppercase()) }.getOrElse { throw BadModelOutput("스크롤 방향 오류") },
                if (json.has("id")) json.id(view) else null)
            "inspect" -> AgentAction.Inspect(json.str("query").take(80))
            "back" -> AgentAction.Back
            "wait" -> AgentAction.Wait
            "media" -> AgentAction.Media(runCatching { MediaKey.valueOf(json.str("key").uppercase()) }
                .getOrElse { throw BadModelOutput("미디어 키 오류") })
            "done" -> AgentAction.Done(json.str("say"))
            "ask" -> AgentAction.Ask(json.str("question"))
            "fail" -> AgentAction.Fail(json.str("reason"))
            else -> throw BadModelOutput("알 수 없는 동작: $name")
        }
        return Decision(note, action, json.str("expect").take(80), json.get("check")?.asBoolean == true)
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()

    private fun JsonObject.id(view: ScreenView): Int {
        val id = get("id")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: throw BadModelOutput("id 없음")
        if (view.element(id) == null) throw BadModelOutput("없는 요소 [$id]")
        return id
    }
}

/**
 * Human-readable description for logs; with [ids] = false (history shown to the model) element
 * numbers are left out, since they refer to an older screen.
 */
fun AgentAction.describe(view: ScreenView?, ids: Boolean = true): String {
    fun el(id: Int) = view?.element(id)?.let { if (ids) "[$id] \"${it.label}\"" else "\"${it.label}\"" } ?: "[$id]"
    return when (this) {
        is AgentAction.OpenApp -> "open_app \"$app\""
        is AgentAction.Click -> "click ${el(id)}"
        is AgentAction.LongClick -> "long_click ${el(id)}"
        is AgentAction.TapEnd -> "click_end ${el(id)}"
        is AgentAction.DoubleTap -> "double_tap ${el(id)}"
        is AgentAction.Type -> "type ${el(id)} \"$text\"" + if (enter) " +enter" else ""
        is AgentAction.Scroll -> "scroll ${dir.name.lowercase()}" + (id?.let { " " + el(it) } ?: "")
        is AgentAction.Inspect -> "inspect \"$query\""
        AgentAction.Back -> "back"
        AgentAction.Wait -> "wait"
        is AgentAction.Media -> "media ${key.name.lowercase()}"
        is AgentAction.Done -> "done \"$say\""
        is AgentAction.Ask -> "ask \"$question\""
        is AgentAction.Fail -> "fail \"$reason\""
    }
}
