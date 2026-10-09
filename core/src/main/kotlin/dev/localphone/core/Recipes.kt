package dev.localphone.core

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/**
 * A learned path: the concrete steps that completed a goal before. Steps are matched by the visible
 * label, not by screen coordinates, so a replay only proceeds while the real screen still matches.
 */
data class RecipeStep(val op: String, val label: String = "", val kind: String = "", val text: String = "",
                      val enter: Boolean = false, val dir: String = "", val app: String = "", val key: String = "")

/**
 * [confirmed]: the user said the result was right. Only confirmed recipes are replayed; the model's
 * own completion check is not trusted enough to turn a path into a habit.
 */
data class Recipe(val key: String, val goal: String, val steps: List<RecipeStep>, val result: String,
                  val created: Long, val uses: Int = 0, val lastUsed: Long = 0, val confirmed: Boolean = false)

object GoalKey {
    private val tail = Regex("(?:해\\s*줘|해\\s*주세요|해\\s*줄래|좀|줘|주세요|해)$")
    fun of(goal: String): String {
        var text = goal.lowercase().replace(Regex("[\\s.,!?~]+"), "")
        repeat(2) { text = text.replace(tail, "") }
        return text
    }
}

class RecipeBook(private val load: () -> String?, private val save: (String) -> Unit) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val type = object : TypeToken<List<Recipe>>() {}.type
    private var cache: MutableList<Recipe>? = null

    @Synchronized fun all(): List<Recipe> = items().toList()
    /** A replayable (user-confirmed) recipe for [goal]. */
    @Synchronized fun find(goal: String): Recipe? = items().firstOrNull { it.key == GoalKey.of(goal) && it.confirmed }
    @Synchronized fun get(goal: String): Recipe? = items().firstOrNull { it.key == GoalKey.of(goal) }
    @Synchronized fun confirm(goal: String) {
        val list = items(); val i = list.indexOfFirst { it.key == GoalKey.of(goal) }
        if (i >= 0) { list[i] = list[i].copy(confirmed = true); persist() }
    }
    @Synchronized fun put(recipe: Recipe) {
        val list = items(); list.removeAll { it.key == recipe.key }; list += recipe; persist()
    }
    @Synchronized fun used(key: String, now: Long) {
        val list = items(); val i = list.indexOfFirst { it.key == key }
        if (i >= 0) { list[i] = list[i].copy(uses = list[i].uses + 1, lastUsed = now); persist() }
    }
    @Synchronized fun remove(key: String) { items().removeAll { it.key == key }; persist() }

    private fun items(): MutableList<Recipe> = cache ?: (runCatching { load()?.let { gson.fromJson<List<Recipe>>(it, type) } }.getOrNull()
        ?: emptyList()).toMutableList().also { cache = it }
    private fun persist() = save(gson.toJson(items()))
}

/** Converts executed actions into recipe steps (labels taken from the screen the action ran on). */
object RecipeRecorder {
    fun step(action: AgentAction, view: ScreenView?): RecipeStep? = when (action) {
        is AgentAction.OpenApp -> RecipeStep("open_app", app = action.app)
        is AgentAction.Click -> view?.element(action.id)?.let { RecipeStep("click", it.label, it.kind.name) }
        is AgentAction.LongClick -> view?.element(action.id)?.let { RecipeStep("long_click", it.label, it.kind.name) }
        is AgentAction.Type -> view?.element(action.id)?.let { RecipeStep("type", it.label, it.kind.name, action.text, action.enter) }
        is AgentAction.Scroll -> RecipeStep("scroll", dir = action.dir.name.lowercase(),
            label = action.id?.let { view?.element(it)?.label }.orEmpty())
        AgentAction.Back -> RecipeStep("back")
        is AgentAction.Media -> RecipeStep("media", key = action.key.name.lowercase())
        AgentAction.Wait, is AgentAction.Inspect, is AgentAction.Done, is AgentAction.Ask, is AgentAction.Fail -> null
    }

    /** Finds the element a recorded step refers to on the current screen. Exact label first. */
    fun locate(step: RecipeStep, view: ScreenView): Element? {
        val kind = runCatching { Kind.valueOf(step.kind) }.getOrNull()
        val sameKind = view.elements.filter { kind == null || it.kind == kind || (it.kind != Kind.INPUT && kind != Kind.INPUT) }
        val exact = sameKind.filter { it.label == step.label }
        if (exact.size == 1) return exact.single()
        val norm = { s: String -> s.replace(Regex("\\s+"), "") }
        val loose = sameKind.filter { norm(it.label) == norm(step.label) }
        if (loose.size == 1) return loose.single()
        // Countdowns and counters change between runs ("안내시작 10" → "안내시작 7"); compare without digits.
        val bare = { s: String -> norm(s).replace(Regex("\\d+"), "") }
        if (bare(step.label).length < 2) return null
        return sameKind.filter { bare(it.label) == bare(step.label) }.singleOrNull()
    }

    fun toAction(step: RecipeStep, view: ScreenView): AgentAction? = when (step.op) {
        "open_app" -> AgentAction.OpenApp(step.app)
        "click" -> locate(step, view)?.let { AgentAction.Click(it.id) }
        "long_click" -> locate(step, view)?.let { AgentAction.LongClick(it.id) }
        "type" -> locate(step, view)?.let { AgentAction.Type(it.id, step.text, step.enter) }
        "scroll" -> AgentAction.Scroll(runCatching { ScrollDir.valueOf(step.dir.uppercase()) }.getOrDefault(ScrollDir.DOWN),
            if (step.label.isBlank()) null else view.lists.firstOrNull { it.label == step.label }?.id)
        "back" -> AgentAction.Back
        "media" -> runCatching { AgentAction.Media(MediaKey.valueOf(step.key.uppercase())) }.getOrNull()
        else -> null
    }
}
