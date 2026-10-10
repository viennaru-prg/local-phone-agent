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
    /** Time, battery, volume, timer, alarm: answered or done by Android directly; null = not handled. */
    suspend fun quick(request: QuickRequest): String? = null
    /** Closes the named app, or the one in front for null. Never this assistant itself. */
    suspend fun closeApp(name: String?, guidanceChecked: Boolean = false): CloseResult = CloseResult.NotAnApp
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
        // Everyday variants: "노래 꺼줘", "음악 좀 멈춰", "다음 노래", "노래 다시 틀어줘".
        val g = GoalText.normalize(goal).replace("좀", "")
        val end = "(?:해줘|해주세요|줘|주세요|해|할래|라)?"
        val music = "(?:음악|노래|곡)(?:을|를)?"
        val track = "(?:곡|노래|음악|거)"
        return when {
            Regex("^(?:$music)?(?:다시|계속)?(?:재생|틀어|들려)$end$").matches(g) ||
                Regex("^$music(?:다시)?켜$end$").matches(g) -> MediaKey.PLAY
            Regex("^$music(?:멈춰|정지|일시정지|꺼|그만)$end$").matches(g) ||
                Regex("^(?:일시정지|정지)$end$").matches(g) -> MediaKey.PAUSE
            Regex("^(?:다음$track|$music?다음$track?)(?:으로)?(?:넘겨|틀어|재생|들려)?$end$").matches(g) ||
                Regex("^$music?넘겨$end$").matches(g) -> MediaKey.NEXT
            Regex("^(?:이전|앞)$track(?:으로)?(?:넘겨|틀어|재생|들려)?$end$").matches(g) -> MediaKey.PREVIOUS
            else -> null
        }
    }

    /**
     * The transport control a goal asks for, also inside a named app ("클립스트림에서 음악 틀어줘"):
     * there the player's own button is pressed on screen instead of a system media key.
     */
    fun mediaKeyIn(goal: String): MediaKey? = simpleMediaKey(goal)
        ?: GoalText.namedApp(goal)?.let { if (GoalScope.multiple(goal)) null else simpleMediaKey(goal.trim().substringAfter("에서").trim()) }

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
        // "안내해 줘" whose place was cut off: ask for it rather than guess.
        if (Regex("안내|길\\s*찾|내비|네비|데려다").containsMatchIn(goal) && !Regex("종료|그만|꺼|끝").containsMatchIn(goal) && GoalText.missingDestination(goal))
            return AgentResult(Outcome.ASK, "어디로 안내할까요?", listOf(HistoryLine("목적지 질문", goal)))
        QuickCommands.parse(goal)?.let { request ->
            listener.progress(when (request) {
                QuickRequest.Time -> "시간 확인 중…"
                QuickRequest.Date -> "날짜 확인 중…"
                QuickRequest.Battery -> "배터리 확인 중…"
                is QuickRequest.Volume -> "볼륨 조절 중…"
                is QuickRequest.Timer -> "타이머 설정 중…"
                is QuickRequest.Alarm -> "알람 설정 중…"
            })
            tools.quick(request)?.let { say ->
                listener.step(StepRecord(0, "android_api", "", "", request.toString(), "완료", "Android 기능으로 바로 처리", 0, 0))
                return AgentResult(Outcome.DONE, say, emptyList())
            }
        }
        AppClose.target(goal)?.let { name ->
            val named = name.ifEmpty { null }
            when (val closed = tools.closeApp(named)) {
                is CloseResult.Closed -> {
                    listener.step(StepRecord(0, "android_api", "", "", "close_app", "완료", "앱 닫기", 0, 0))
                    return AgentResult(Outcome.DONE, closed.say, emptyList())
                }
                // The map: end a running drive first. "종료해줘" during guidance means the drive; with none
                // running (or "네이버 지도 종료해줘"), the map itself closes.
                CloseResult.NavigationApp -> {
                    val ended = agent.run("내비게이션 꺼줘")
                    if (ended.outcome != Outcome.DONE) return ended
                    val wasGuiding = Harness.endedGuidance(ended.history)
                    if (wasGuiding && named == null) return ended
                    val after = tools.closeApp(named, guidanceChecked = true)
                    return if (after is CloseResult.Closed) AgentResult(Outcome.DONE, (if (wasGuiding) "길안내를 끝내고 " else "") + after.say, ended.history)
                        else ended
                }
                CloseResult.NotAnApp -> Unit
            }
        }
        // A name with no request ("이름 모임") would only send the screen agent wandering the app in front.
        // Questions ("몇 시야?") and short music commands ("다음 노래") are requests too.
        if (!GoalText.hasRequest(goal) && !QuickCommands.isQuestion(goal) && Router.mediaKeyIn(goal) == null) return AgentResult(Outcome.FAILED, "'${goal.trim()}'을(를) 어떻게 할지 함께 말씀해 주세요. 예: 회사로 안내해줘", emptyList())
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
            is Route.Navigate -> {
                listener.progress("${route.place.name} 길안내 준비 중…")
                tools.navigate(route.place)?.let { AgentResult(Outcome.DONE, it, emptyList()) }
                    ?: agent.run(goal) // deep link failed: fall back to operating the map on screen
            }
            // The key was sent but not confirmed: the screen agent finishes it, knowing a key already went out.
            is Route.Media -> {
                listener.progress("음악 조작 중…")
                tools.media(route.key)?.let { AgentResult(Outcome.DONE, it, emptyList()) }
                    ?: agent.run(goal, listOf(HistoryLine("media ${route.key.name.lowercase()}", "키 전송, 확인 전")))
            }
            Route.Screen -> {
                if ((Router.isNavigationGoal(goal) && Router.usesNaver(goal)) || QuickCommands.etaTarget(goal) != null) {
                    val opened = tools.prepareNavigation()
                    listener.step(StepRecord(0, "navigation", "", "", "open_map", if (opened) "지도 전면 화면 확인됨" else "화면 AI로 앱 실행", "미등록 장소는 실제 지도에서 확인", 0, routeMs))
                }
                agent.run(goal)
            }
        }
    }
}
