package dev.localphone.core

sealed interface Action {
    data class Navigate(val destination: String) : Action
    data object MediaResume : Action
    data object MediaPause : Action
    data object MediaNext : Action
    /** An app goal is executable without an app-specific tool registration. */
    data class AppTask(val appName: String, val goal: String) : Action
    sealed interface Device : Action
    data class OpenApp(val appName: String) : Device
    data class SetAlarm(val hour: Int, val minute: Int) : Device
    data class SetTimer(val seconds: Int) : Device
    data class OpenSettings(val page: SettingsPage) : Device
}

data class ToolPlan(val actions: List<Action>, val unsupportedReason: String? = null)
interface IntentPlanner { suspend fun plan(utterance: String): ToolPlan }

object CommandSafety {
    private val waypoint = Regex("들렀|들러|경유|거쳐|갔다가|가고\\s*.*(?:가자|가줘)")
    private val negation = Regex("하지\\s*마|가지\\s*마|가지마|틀지\\s*마|재생하지|취소|말고|안\\s*가|가면(?!서)|갈까|가도|(?:가줘|가자|찍어|틀어|재생).*(?:라고|라는|하면|뜻|예시|설명|번역)")
    fun blockedReason(text: String): String? = when {
        text.length !in 1..1000 -> "명령 길이를 확인해 주세요."
        negation.containsMatchIn(text) -> "취소·조건·질문이 포함된 문장입니다. 실행할 명령을 분명하게 입력해 주세요."
        else -> null
    }
    // A limitation of the single-destination Intent is not a safety denial of the original goal.
    fun needsScreenGoal(text: String) = waypoint.containsMatchIn(text)
}

/** Deterministic development mode. This class is explicitly not an LLM. */
class BasicCommandPlanner : IntentPlanner {
    override suspend fun plan(utterance: String): ToolPlan = parse(utterance)
    companion object {
    /** Pure recognition is also used to detect missing parts of an explicit compound goal. */
    fun parse(utterance: String): ToolPlan {
        val text = utterance.trim().trimEnd('.', '。', '!')
        CommandSafety.blockedReason(text)?.let { return ToolPlan(emptyList(), it) }
        if (CommandSafety.needsScreenGoal(text)) return GoalRequests.fallback(text)
        MediaCommands.parse(text)?.let { return ToolPlan(listOf(it)) }
        val media = Regex("(?:노래|음악)\\s*(?:를\\s*)?(?:재생(?:해줘|해|해 줘)?|틀어(?:줘)?|켜(?:줘)?)|(?:미디어|음악)\\s*이어\\s*(?:재생|틀어)")
        val mediaFound = media.containsMatchIn(text)
        val remainder = media.replace(text, "").trim(' ', ',', '.', '!')
        val match = NavigationLanguage.whole.matchEntire(remainder)
            ?: Regex("^(.+?)\\s*가면서\\s*$").matchEntire(remainder)
        val actions = mutableListOf<Action>()
        match?.groupValues?.get(1)?.trim()?.let { destination ->
            val phrase = destination.trim()
            if (phrase.isNotBlank()) actions += Action.Navigate(phrase)
        }
        if (mediaFound && (match != null || remainder.isBlank())) actions += Action.MediaResume
        if (actions.isEmpty()) PhoneCommands.parse(text)?.let { return it }
        return if (actions.isEmpty()) ToolPlan(emptyList(), "명령을 확실히 이해하지 못했습니다. 기본 모드 예: 다음 곡 틀어 / 노래 멈춰 / 시계 앱 열어줘 / 오전 7시 알람 맞춰줘 / 집으로 가자") else ToolPlan(actions)
    }
    }
}

enum class MediaCommand { RESUME, PAUSE, NEXT }
fun Action.mediaCommand(): MediaCommand? = when (this) {
    Action.MediaResume -> MediaCommand.RESUME
    Action.MediaPause -> MediaCommand.PAUSE
    Action.MediaNext -> MediaCommand.NEXT
    else -> null
}

/** Whole-utterance matches prevent a save/conditional/quoted request from becoming a skip. */
object MediaCommands {
    private val next = Regex("^(?:다음\\s*(?:곡|노래|음악)|(?:노래|음악)\\s*다음\\s*곡)(?:\\s*(?:으로\\s*)?(?:틀어|재생해|넘겨|넘겨줘|넘겨 줘)(?:\\s*줘)?)?$")
    private val pause = Regex("^(?:노래|음악|미디어)(?:를|을)?\\s*(?:멈춰|꺼|중지해|일시\\s*정지(?:해)?)(?:\\s*줘)?$")
    private val resume = Regex("^(?:노래|음악|미디어)(?:를|을)?\\s*(?:이어\\s*)?(?:재생(?:해)?|틀어|켜)(?:\\s*줘)?$")
    fun parse(text: String): Action? = when {
        next.matches(text.trim()) -> Action.MediaNext
        pause.matches(text.trim()) -> Action.MediaPause
        resume.matches(text.trim()) -> Action.MediaResume
        else -> null
    }
}

data class RawToolCall(val name: String, val arguments: Map<String, Any?>)

object ToolPlanDecoder {
    fun decode(calls: List<RawToolCall>): ToolPlan = PhoneTools.registry.decode(calls)
}

sealed interface PolicyDecision {
    data class Ready(val destination: PlaceCandidate?, val resumeMedia: Boolean,
                     val deviceCommands: List<PreparedDeviceCommand> = emptyList(),
                     val mediaCommand: MediaCommand? = if (resumeMedia) MediaCommand.RESUME else null,
                     val navigationGoal: String? = null,
                     val pendingDevices: List<Action.Device> = emptyList(),
                     val appTasks: List<Action.AppTask> = emptyList()) : PolicyDecision
    data class Blocked(val message: String, val resolution: Resolution? = null,
                       val appChoices: List<AppCandidate> = emptyList()) : PolicyDecision
}

/** Resolve the whole plan before any external side effect. */
class PolicyGate(private val resolver: PlaceResolver, private val device: DevicePort? = null) {
    suspend fun prepare(plan: ToolPlan, selectedAppId: String? = null): PolicyDecision {
        plan.unsupportedReason?.let { return PolicyDecision.Blocked(it) }
        if (plan.actions.isEmpty() || plan.actions.size > ToolRegistry.MAX_STEPS || plan.actions.distinct().size != plan.actions.size) return PolicyDecision.Blocked("실행할 명령을 확인하지 못했습니다.")
        val navigation = plan.actions.filterIsInstance<Action.Navigate>()
        if (navigation.size > 1) return PolicyDecision.Blocked("아직 경유지는 지원하지 않습니다.")
        val commands = plan.actions.filterIsInstance<Action.Device>()
        if (commands.any { it is Action.SetAlarm && (it.hour !in 0..23 || it.minute !in 0..59) || it is Action.SetTimer && it.seconds !in 1..86400 })
            return PolicyDecision.Blocked("시간 범위를 확인해 주세요.")
        val appTasks = plan.actions.filterIsInstance<Action.AppTask>()
        val mediaCommands = plan.actions.mapNotNull { it.mediaCommand() }
        if (mediaCommands.size > 1) return PolicyDecision.Blocked("음악 동작을 하나만 요청해 주세요. 아직 실행하지 않았습니다.")
        val resume = Action.MediaResume in plan.actions
        val resolution = navigation.singleOrNull()?.let { resolver.resolve(it.destination) }
        // Missing cache entries or optional API credentials are unavailable fast paths, not policy denials.
        // A real collision remains a clarification; never guess among private places.
        if (resolution?.status == ResolutionStatus.AMBIGUOUS && resolution.candidates.size > 1)
            return PolicyDecision.Blocked(resolution.message.ifBlank { "어느 목적지인가요?" }, resolution)
        val destination = resolution?.resolved
        if (destination != null && !destination.coordinates.supportedByNaver()) return PolicyDecision.Blocked("네이버지도 내비게이션 지원 범위 밖의 좌표입니다.")
        val prepared = mutableListOf<PreparedDeviceCommand>()
        val pending = mutableListOf<Action.Device>()
        for (command in commands) {
            when (val check = device?.prepare(command, selectedAppId)) {
                is DeviceCheck.Ready -> prepared += check.command
                is DeviceCheck.Blocked -> if (check.appChoices.isNotEmpty()) return PolicyDecision.Blocked(check.message, appChoices = check.appChoices) else pending += command
                null -> pending += command
            }
        }
        return PolicyDecision.Ready(destination, resume, prepared, mediaCommands.singleOrNull(),
            navigation.singleOrNull()?.destination, pending, appTasks)
    }
}

interface NavigationPort {
    fun canLaunch(destination: PlaceCandidate): Boolean
    fun launch(destination: PlaceCandidate): Boolean
}
interface MediaPort {
    fun resume(): Boolean
    fun pause(): Boolean = false
    fun next(): Boolean = false
    fun canExecute(command: MediaCommand): Boolean = command == MediaCommand.RESUME
    fun dispatch(command: MediaCommand): Boolean = when (command) {
        MediaCommand.RESUME -> resume()
        MediaCommand.PAUSE -> pause()
        MediaCommand.NEXT -> next()
    }
}

data class ExecutionResult(val launched: Boolean, val mediaResumed: Boolean, val message: String,
                           val deviceDispatched: Boolean = false, val mediaDispatched: Boolean = mediaResumed,
                           val success: Boolean = launched || mediaDispatched || deviceDispatched)

class ActionExecutor(private val navigation: NavigationPort, private val media: MediaPort, private val device: DevicePort? = null) {
    fun execute(ready: PolicyDecision.Ready, confirmed: Boolean = false): ExecutionResult {
        if ((ready.navigationGoal != null && ready.destination == null) || ready.pendingDevices.isNotEmpty() || ready.appTasks.isNotEmpty())
            return ExecutionResult(false, false, "구조화된 빠른 경로로 해결되지 않았습니다. 목표 실행기가 앱 화면에서 계속 수행해야 합니다.", success = false)
        if (!confirmed && ready.deviceCommands.any { it.requiresConfirmation }) {
            return ExecutionResult(false, false, "이 작업은 확인이 필요합니다. 아직 요청을 전달하지 않았습니다.")
        }
        val place = ready.destination
        if (place != null && (!place.coordinates.supportedByNaver() || !navigation.canLaunch(place))) {
            return ExecutionResult(false, false, "네이버지도를 설치하거나 실행 가능 상태를 확인해 주세요.")
        }
        if (ready.deviceCommands.any { device?.canExecute(it) != true }) {
            return ExecutionResult(false, false, "실행할 앱을 찾지 못했습니다. 아직 명령을 실행하지 않았습니다.")
        }
        if (ready.mediaCommand?.let { !media.canExecute(it) } == true) {
            return ExecutionResult(false, false, "선택한 음악 앱에서 이 동작을 사용할 수 없습니다. 아직 요청을 보내지 않았습니다.")
        }
        // Launch navigation before media; media uses a controller and never brings its app forward.
        val launched = place?.let(navigation::launch) ?: false
        if (place != null && !launched) return ExecutionResult(false, false, "네이버지도를 열지 못했습니다.")
        for (command in ready.deviceCommands) {
            if (device?.dispatch(command) != true) return ExecutionResult(launched, false, "${command.summary} 요청을 보내지 못했습니다.", success = false)
        }
        val deviceDispatched = ready.deviceCommands.isNotEmpty()
        val mediaDispatched = ready.mediaCommand?.let(media::dispatch) ?: false
        val resumed = ready.mediaCommand == MediaCommand.RESUME && mediaDispatched
        return ExecutionResult(launched, resumed, when {
            ready.mediaCommand != null && !mediaDispatched -> "음악 제어 요청을 보내지 못했습니다. 음악 앱과 알림 접근 권한을 확인해 주세요."
            launched && resumed -> "네이버지도 실행 및 음악 재생을 요청했습니다."
            launched -> "네이버지도 내비게이션을 열었습니다."
            resumed -> "음악 재생을 요청했습니다."
            mediaDispatched && ready.mediaCommand == MediaCommand.PAUSE -> "음악 일시정지를 요청했습니다."
            mediaDispatched -> "다음 곡을 요청했습니다."
            deviceDispatched -> ready.deviceCommands.joinToString("\n") { "${it.summary} 요청을 전달했습니다. 최종 동작은 열린 앱에서 확인해 주세요." }
            else -> "실행할 작업이 없습니다."
        }, deviceDispatched, mediaDispatched, success = (ready.destination == null || launched) &&
            (ready.mediaCommand == null || mediaDispatched) && (launched || mediaDispatched || deviceDispatched))
    }
}
