package dev.localphone.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/** A place the user registered once ("회사" → address → coordinates). Stored only on the phone. */
data class Place(val name: String, val aliases: List<String> = emptyList(), val address: String = "",
                 val lat: Double = 0.0, val lng: Double = 0.0) {
    val names get() = listOf(name) + aliases
}

class PlaceBook(private val load: () -> String?, private val save: (String) -> Unit) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val type = object : TypeToken<List<Place>>() {}.type
    private var cache: MutableList<Place>? = null

    @Synchronized fun all(): List<Place> = items().toList()
    @Synchronized fun put(place: Place) { val l = items(); l.removeAll { it.name == place.name }; l += place; persist() }
    @Synchronized fun remove(name: String) { items().removeAll { it.name == name }; persist() }

    /** The registered place named in [goal]; the longest matching name wins ("회사2" over "회사"). */
    @Synchronized fun mentionedIn(goal: String): Place? {
        val g = GoalText.normalize(goal)
        return items().flatMap { p -> p.names.map { n -> p to GoalText.normalize(n) } }
            .filter { (_, n) -> n.length >= 1 && g.contains(n) }
            .maxByOrNull { (_, n) -> n.length }?.first
    }
    @Synchronized fun named(name: String): Place? = items().firstOrNull { p -> p.names.any { GoalText.normalize(it) == GoalText.normalize(name) } }

    private fun items(): MutableList<Place> = cache ?: (runCatching { load()?.let { gson.fromJson<List<Place>>(it, type) } }.getOrNull()
        ?: emptyList()).toMutableList().also { cache = it }
    private fun persist() = save(gson.toJson(items()))
}

/** What a command needs: a direct tool when one exists, otherwise the on-screen agent. */
sealed interface Route {
    data class Navigate(val place: Place) : Route
    data class Media(val key: MediaKey) : Route
    data object Screen : Route
}

/** Direct, reliable operations the phone offers (deep links, media keys). */
interface Tools {
    /** Starts turn-by-turn guidance to [place]; returns a sentence for the user, or null on failure. */
    suspend fun navigate(place: Place): String?
    suspend fun media(key: MediaKey): String?
    /** Open the map through Android before resolving an unregistered destination on screen. */
    suspend fun prepareNavigation(): Boolean = false
    suspend fun openDirect(goal: String): String? = null
}

object Router {
    private val navVerb = Regex("가자|가\\s*줘|가요|안내|길\\s*찾|네비|내비|데려|가는\\s*길")
    fun isNavigationGoal(goal: String): Boolean {
        return ShortcutGoals.navigationTarget(goal) != null
    }
    fun usesNaver(goal: String): Boolean {
        val app = GoalText.namedApp(goal)?.let(GoalText::normalize)
        if (app != null && app !in setOf("네이버지도", "네이버", "지도", "navermap")) return false
        return !Regex("카카오맵|카카오지도|티맵|tmap|t맵", RegexOption.IGNORE_CASE).containsMatchIn(goal)
    }

    /**
     * The obvious case needs no model: a registered place plus a navigation word ("회사로 가자").
     * Returns null when the command is not obviously navigation.
     */
    fun quick(goal: String, places: PlaceBook): Route? {
        if (!isNavigationGoal(goal) || !usesNaver(goal)) return null
        val target = ShortcutGoals.navigationTarget(goal) ?: return null
        return places.named(target)?.let { Route.Navigate(it) }
    }

    private val toolCue = Regex("음악|노래|곡|재생|틀어|멈춰|정지|일시|다음|이전|볼륨|가자|가줘|안내|길|네비|내비|데려")
    fun mightUseTool(goal: String) = toolCue.containsMatchIn(goal)

    /** Tool grammars constrain JSON, not intent. Reject an unrelated model-selected operation. */
    fun validate(goal: String, route: Route, places: PlaceBook): Route = when (route) {
        is Route.Media -> if (allowsMedia(goal)) route else Route.Screen
        is Route.Navigate -> if (isNavigationGoal(goal) && usesNaver(goal) &&
            ShortcutGoals.navigationTarget(goal)?.let(places::named) == route.place) route else Route.Screen
        Route.Screen -> route
    }

    fun simpleMediaKey(goal: String): MediaKey? {
        if (GoalScope.multiple(goal) || GoalText.namedApp(goal) != null) return null
        val g = GoalText.normalize(goal)
        val end = "(?:해줘|해주세요|줘|주세요|해|할래)?"
        return when {
            Regex("^(?:(?:음악|노래|곡)(?:을|를)?)?(?:재생|틀어)$end$").matches(g) -> MediaKey.PLAY
            Regex("^(?:음악|노래|곡)(?:을|를)?(?:멈춰|정지|일시정지)$end$").matches(g) ||
                Regex("^일시정지$end$").matches(g) -> MediaKey.PAUSE
            Regex("^다음곡(?:으로)?(?:넘겨|넘겨줘|넘겨주세요|틀어|재생)?$end$").matches(g) -> MediaKey.NEXT
            Regex("^이전곡(?:으로)?(?:넘겨|넘겨줘|넘겨주세요|틀어|재생)?$end$").matches(g) -> MediaKey.PREVIOUS
            else -> null
        }
    }

    fun allowsMedia(goal: String): Boolean = simpleMediaKey(goal) != null ||
        (GoalScope.multiple(goal) && GoalScope.parts(goal).any { simpleMediaKey(it) != null })

    /** Short classification prompt for everything else. Place names are offered as a closed list. */
    fun prompt(goal: String, places: List<Place>): ModelPrompt {
        val names = places.joinToString(", ") { p -> p.names.joinToString("/") }.ifBlank { "(없음)" }
        val system = """
명령을 보고 처리 방법 하나를 JSON으로 고른다.
- navigate: 등록된 장소로 길안내를 시작하라는 명령. place는 등록된 장소 이름 중 하나.
- media: 지금 나오는 음악을 재생/일시정지/다음 곡/이전 곡으로 바꾸라는 명령(앱이나 노래를 고르는 말이 없을 때).
- screen: 그 밖의 모든 일(앱에서 무엇을 찾기, 노래 검색·추가, 설정 바꾸기, 등록되지 않은 장소 등).
예시:
명령: 회사로 가자 → {"tool":"navigate","place":"회사"}
명령: 노래 멈춰 → {"tool":"media","key":"pause"}
명령: 다음 곡 → {"tool":"media","key":"next"}
명령: 아이유 노래 재생목록에 추가해줘 → {"tool":"screen"}
명령: 와이파이 꺼줘 → {"tool":"screen"}
""".trim()
        return ModelPrompt(system, "등록된 장소: $names\n명령: $goal\nJSON:")
    }

    fun grammar(places: List<Place>): String {
        val names = places.flatMap { it.names }.distinct()
        val navigate = if (names.isEmpty()) "" else
            """ | "\"navigate\",\"place\":" (${names.joinToString(" | ") { "\"\\\"${it.replace("\"", "")}\\\"\"" }})"""
        return """
root ::= "{\"tool\":" ("\"screen\""$navigate | "\"media\",\"key\":" ("\"play\"" | "\"pause\"" | "\"next\"" | "\"previous\"")) "}"
""".trim()
    }

    fun parse(raw: String, places: PlaceBook): Route {
        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return Route.Screen
        return when (json.get("tool")?.asString) {
            "navigate" -> json.get("place")?.asString?.let(places::named)?.let { Route.Navigate(it) } ?: Route.Screen
            "media" -> runCatching { Route.Media(MediaKey.valueOf(json.get("key").asString.uppercase())) }.getOrDefault(Route.Screen)
            else -> Route.Screen
        }
    }
}

/**
 * Tool first, screen second: a command that a direct tool can do is done in a second or two;
 * everything else goes to the on-screen [Agent].
 */
class Assistant(
    private val model: LanguageModel,
    private val tools: Tools,
    private val places: PlaceBook,
    private val agent: Agent,
    private val listener: AgentListener = object : AgentListener {},
) {
    suspend fun run(goal: String, previous: List<HistoryLine> = emptyList()): AgentResult {
        if (previous.isNotEmpty()) return agent.run(goal, previous)
        if (GoalText.isChatter(goal)) return AgentResult(Outcome.FAILED, "할 일을 알아듣지 못했어요. 다시 말씀해 주세요.", emptyList())
        tools.openDirect(goal)?.let {
            listener.step(StepRecord(0, "android_api", "", "", "open", "요청한 앱·화면 확인됨", "단일 실행 목표를 Android API로 처리", 0, 0))
            return AgentResult(Outcome.DONE, it, emptyList())
        }
        // Only commands that could be a tool call (music or navigation words) are worth a model call;
        // the rest go straight to the screen agent, keeping its cached prompt warm.
        var raw = ""
        var routeMs = 0L
        // With no local destination, resolve it in the actual map UI. Do not ask a classifier whose
        // grammar omits navigation: it picked music PAUSE for '회사로 안내해 줘' on the S25.
        val candidate = Router.quick(goal, places) ?: Router.simpleMediaKey(goal)?.let { Route.Media(it) } ?: Route.Screen
        val route = Router.validate(goal, candidate, places)
        val reason = when {
            candidate != route -> "명령과 맞지 않는 Tool을 거절하고 화면 작업으로 전환"
            Router.isNavigationGoal(goal) && route == Route.Screen -> "로컬 장소 없음: 지도 저장 장소·길찾기·검색에서 목적지 확인"
            else -> "목표에 맞는 처리 경로 선택"
        }
        listener.step(StepRecord(0, "router", "", raw, route.toString(), "", reason, routeMs, routeMs))
        return when (route) {
            is Route.Navigate -> tools.navigate(route.place)?.let { AgentResult(Outcome.DONE, it, emptyList()) }
                ?: agent.run(goal) // deep link failed: fall back to operating the map on screen
            is Route.Media -> tools.media(route.key)?.let { AgentResult(Outcome.DONE, it, emptyList()) } ?: agent.run(goal)
            Route.Screen -> {
                if (Router.isNavigationGoal(goal) && Router.usesNaver(goal)) {
                    val opened = tools.prepareNavigation()
                    listener.step(StepRecord(0, "navigation", "", "", "open_map", if (opened) "지도 전면 화면 확인됨" else "화면 AI로 앱 실행", "미등록 장소는 실제 지도에서 확인", 0, routeMs))
                }
                agent.run(goal)
            }
        }
    }
}
