package dev.localphone.agent.runtime

import android.app.Activity
import dev.localphone.agent.AgentApplication
import dev.localphone.core.*
import kotlinx.coroutines.*

enum class EngineStage { PLANNING, POLICY_CHECK }
enum class ResultDisposition { BACKGROUND_ACTION, FOREGROUND_NAVIGATION, FOREGROUND_TOOL }
data class AgentFailure(val state: InvocationState, val message: String)
data class PreparedRequest(val plan: ToolPlan, val decision: PolicyDecision,
                           val failure: AgentFailure? = null, val clarification: ClarificationRequest? = null,
                           val interpretation: String = "GOAL_WITH_STRUCTURED_FAST_PATH", val speechConfidence: Float? = null)
data class AgentOutcome(val execution: ExecutionResult?, val disposition: ResultDisposition,
                        val failure: AgentFailure? = null, val evidence: List<String> = emptyList())

/** Goals own the execution lifecycle. Fast-path availability is not a policy precondition. */
class AgentEngine(private val activity: Activity, private val graph: AgentApplication) {
    val navigation = NaverNavigation(activity)
    val media = AndroidMedia(activity, graph.settings)
    private val device = AndroidDeviceTools(activity)
    private val policy = PolicyGate(graph.resolver, device)
    val profile = ProfileScope(activity)
    var externalExecution = false
        private set
    private val previousPackage = graph.uiAutomation.foregroundPackage()
    suspend fun speechContext(): SpeechContext = withContext(Dispatchers.IO) {
        val playback = runCatching { media.controllers().firstOrNull()?.playbackState?.state?.toString() }.getOrNull()
        SpeechVocabulary.build(graph, device.apps(), previousPackage, playback)
    }
    suspend fun prepareSpeech(speech: ResolvedTranscript, useModel: Boolean, onStage: (EngineStage) -> Unit = {}): PreparedRequest {
        if (speech.requiresClarification) return blocked(InvocationState.LOW_CONFIDENCE, speech.clarification)
            .copy(speechConfidence = speech.confidence)
        return prepare(speech.selectedText, useModel, onStage).copy(speechConfidence = speech.confidence)
    }
    suspend fun prepare(utterance: String, useModel: Boolean, onStage: (EngineStage) -> Unit = {}): PreparedRequest {
        onStage(EngineStage.PLANNING)
        CommandSafety.blockedReason(utterance)?.let { reason ->
            onStage(EngineStage.POLICY_CHECK); return blocked(InvocationState.POLICY_BLOCKED, reason)
        }
        var plan = BasicCommandPlanner().plan(utterance)
        var interpretation = "DETERMINISTIC_GOAL_FAST_PATH"
        if (plan.actions.isEmpty()) {
            if (plan.unsupportedReason?.let { it.startsWith("유효한 알람") || it.startsWith("타이머는") } == true) {
                onStage(EngineStage.POLICY_CHECK); return blocked(InvocationState.LOW_CONFIDENCE, plan.unsupportedReason!!)
            }
            if (plan.unsupportedReason?.contains("오전인지 오후인지") == true) {
                onStage(EngineStage.POLICY_CHECK); return blocked(InvocationState.AMBIGUOUS, plan.unsupportedReason!!)
            }
            if (useModel && graph.planner.prepare().available) {
                plan = graph.planner.plan(utterance); interpretation = "FUNCTIONGEMMA_GOAL"
            }
            if (plan.actions.isEmpty()) { plan = GoalRequests.fallback(utterance); interpretation = "ORIGINAL_GOAL_UI_FALLBACK" }
        }
        onStage(EngineStage.POLICY_CHECK)
        return preparePlan(plan).copy(interpretation = interpretation)
    }
    suspend fun preparePlan(plan: ToolPlan, selectedAppId: String? = null): PreparedRequest {
        val decision = withContext(Dispatchers.IO) { policy.prepare(plan, selectedAppId) }
        if (decision is PolicyDecision.Ready) return PreparedRequest(plan, decision)
        decision as PolicyDecision.Blocked
        val resolution = decision.resolution
        val state = if (resolution?.status == ResolutionStatus.AMBIGUOUS || decision.appChoices.isNotEmpty()) InvocationState.AMBIGUOUS
            else if (plan.unsupportedReason != null) InvocationState.LOW_CONFIDENCE else InvocationState.POLICY_BLOCKED
        val clarification = if (resolution?.status == ResolutionStatus.AMBIGUOUS && resolution.candidates.isNotEmpty())
            ClarificationRequest(plan, resolution.candidates, "어느 장소인가요? " + resolution.candidates.take(3).joinToString(" / ") { it.name }) else null
        return PreparedRequest(plan, decision, AgentFailure(state, decision.message), clarification)
    }
    fun clarify(request: ClarificationRequest, utterance: String): PreparedRequest {
        val selected = request.select(utterance) ?: return blocked(InvocationState.AMBIGUOUS, "장소를 확정하지 못했습니다.")
        if (!selected.coordinates.supportedByNaver()) return blocked(InvocationState.POLICY_BLOCKED, "지원 범위 밖의 장소입니다.")
        val command = request.originalPlan.actions.mapNotNull { it.mediaCommand() }.singleOrNull()
        return PreparedRequest(request.originalPlan, PolicyDecision.Ready(selected, command == MediaCommand.RESUME, mediaCommand = command,
            navigationGoal = request.originalPlan.actions.filterIsInstance<Action.Navigate>().singleOrNull()?.destination ?: selected.name))
    }
    fun preflight(ready: PolicyDecision.Ready): AgentFailure? {
        if (!profile.canAct()) return AgentFailure(InvocationState.AUTH_REQUIRED, "잠금 해제 후 다시 호출해 주세요.")
        if (ready.deviceCommands.any { it.requiresConfirmation }) return AgentFailure(InvocationState.POLICY_BLOCKED, "이 작업은 확인이 필요합니다.")
        return null
    }
    suspend fun execute(ready: PolicyDecision.Ready, confirmed: Boolean = false): AgentOutcome {
        val disposition = when {
            ready.navigationGoal != null || ready.destination != null -> ResultDisposition.FOREGROUND_NAVIGATION
            ready.deviceCommands.isNotEmpty() || ready.pendingDevices.isNotEmpty() || ready.appTasks.isNotEmpty() -> ResultDisposition.FOREGROUND_TOOL
            else -> ResultDisposition.BACKGROUND_ACTION
        }
        val checked = if (confirmed) { if (!profile.canAct()) AgentFailure(InvocationState.AUTH_REQUIRED, "잠금 해제가 필요합니다.") else null } else preflight(ready)
        if (checked != null) return AgentOutcome(null, disposition, checked)
        var launched = false; var dispatched = false; var mediaDispatched = false
        val messages = mutableListOf<String>(); val evidence = mutableListOf<String>()
        externalExecution = true
        try {
            val navGoal = ready.navigationGoal ?: ready.destination?.name
            if (navGoal != null) {
                currentCoroutineContext().ensureActive()
                if (!device.localPackage(NaverLinks.PACKAGE)) throw UiUnavailable(InvocationState.EXECUTION_FAILED, "이 설치 공간에서 네이버지도를 찾지 못했습니다.")
                val destination = ready.destination
                val linked = destination?.let { navigation.canLaunch(it) && navigation.launch(it) } == true
                launched = linked || navigation.openMap() || device.openPackage(NaverLinks.PACKAGE)
                if (!launched) throw UiUnavailable(InvocationState.EXECUTION_FAILED, "이 설치 공간에서 네이버지도를 찾거나 열지 못했습니다.")
                val result = NaverUiGoal(graph, navigation).navigate(navGoal, destination, launched)
                messages += result.message; evidence += result.evidence
            }
            for (command in ready.deviceCommands) {
                currentCoroutineContext().ensureActive(); if (!profile.canAct()) throw UiUnavailable(InvocationState.AUTH_REQUIRED, "잠금으로 작업을 중단했습니다.")
                if (device.canExecute(command) && device.dispatch(command)) {
                    dispatched = true; messages += "${command.summary} 요청을 전달했습니다. 최종 동작은 열린 앱에서 확인해 주세요."
                    evidence += "ANDROID_INTENT_DISPATCHED"
                } else {
                    val result = executeDeviceUi(command.action); dispatched = true; messages += result.message; evidence += result.evidence
                }
            }
            for (action in ready.pendingDevices) {
                val result = executeDeviceUi(action); dispatched = true; messages += result.message; evidence += result.evidence
            }
            for (task in ready.appTasks) {
                val app = resolveApp(task.appName, task.goal)
                val result = GenericUiGoal(graph).execute(app.id, task.goal) { device.openPackage(app.id) }
                dispatched = true; messages += result.message; evidence += result.evidence
            }
            val mediaCommand = ready.mediaCommand
            if (mediaCommand != null) {
                currentCoroutineContext().ensureActive(); if (!profile.canAct()) throw UiUnavailable(InvocationState.AUTH_REQUIRED, "잠금으로 음악 작업을 중단했습니다.")
                mediaDispatched = media.canExecute(mediaCommand) && media.dispatch(mediaCommand)
                if (mediaDispatched) { messages += "음악 제어 요청을 전달했습니다."; evidence += "MEDIASESSION_DISPATCHED" }
                else {
                    val app = resolveMusic()
                    val goal = when (mediaCommand) { MediaCommand.RESUME -> "음악 재생해줘"; MediaCommand.PAUSE -> "음악 일시정지해줘"; MediaCommand.NEXT -> "다음 곡 틀어줘" }
                    val result = GenericUiGoal(graph).execute(app.id, goal, mediaCommand) { device.openPackage(app.id) }
                    mediaDispatched = true; messages += result.message; evidence += result.evidence
                    if (launched && !device.openPackage(NaverLinks.PACKAGE)) messages += "지도 화면으로 돌아가지 못했습니다."
                }
            }
            val result = ExecutionResult(launched, mediaDispatched && ready.mediaCommand == MediaCommand.RESUME,
                messages.joinToString("\n"), dispatched, mediaDispatched, messages.isNotEmpty())
            return AgentOutcome(result, disposition, if (!result.success) AgentFailure(InvocationState.EXECUTION_FAILED, "실행할 목표가 없습니다.") else null, evidence)
        } catch (timeout: TimeoutCancellationException) {
            val failure = AgentFailure(InvocationState.EXECUTION_FAILED, "화면 작업 시간 안에 완료를 확인하지 못했습니다.")
            return AgentOutcome(ExecutionResult(launched, false, failure.message, dispatched, mediaDispatched, false), disposition, failure, evidence)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: UiUnavailable) {
            return AgentOutcome(ExecutionResult(launched, false, failure.message, dispatched, mediaDispatched, false), disposition,
                AgentFailure(failure.state, failure.message), evidence)
        } finally {
            externalExecution = false
            if (graph.modelFile.isFile) withContext(NonCancellable) { graph.planner.close() }
        }
    }
    private suspend fun executeDeviceUi(action: Action.Device): UiGoalResult {
        if (action is Action.OpenApp) {
            val app = resolveApp(action.appName, "${action.appName} 앱 열기")
            if (device.openPackage(app.id)) return UiGoalResult("${app.name} 앱을 열었습니다.", "LAUNCHER_INTENT_DISPATCHED")
            throw UiUnavailable(InvocationState.EXECUTION_FAILED, "앱 실행을 완료하지 못했습니다.")
        }
        val app = resolveApp(if (action is Action.OpenSettings) "설정" else "시계", action.toString())
        val goal = when (action) {
            is Action.SetAlarm -> "%02d시 %02d분 한 번 울리는 알람 설정".format(action.hour, action.minute)
            is Action.SetTimer -> "${action.seconds}초 타이머 시작"
            is Action.OpenSettings -> "${action.page.displayName} 열어줘"
            is Action.OpenApp -> error("Handled above")
        }
        return GenericUiGoal(graph).execute(app.id, goal) { device.openPackage(app.id) }
    }
    private suspend fun resolveApp(name: String, goal: String): AppCandidate {
        val inferred = if (name.isNotBlank()) name else when {
            Regex("음악|노래|곡").containsMatchIn(goal) -> return resolveMusic()
            Regex("알람|타이머").containsMatchIn(goal) -> "시계"
            else -> ""
        }
        if (inferred.isBlank()) {
            val foreground = previousPackage ?: graph.uiAutomation.foregroundPackage()
            device.apps().singleOrNull { it.id == foreground }?.let { return it }
            throw UiUnavailable(InvocationState.AMBIGUOUS, "현재 화면에서 작업할 앱을 확정하지 못했습니다. 어느 앱에서 수행할까요?")
        }
        val candidates = device.matchingApps(inferred)
        return graph.uiAutomation.chooseApps(candidates, "'$inferred'에 맞는 앱이 여러 개입니다. 어느 앱인가요?")
            ?: throw UiUnavailable(InvocationState.EXECUTION_FAILED, "설치된 앱과 실행 가능한 Android 경로에서 '$inferred' 앱을 찾지 못했습니다.")
    }
    private suspend fun resolveMusic(): AppCandidate {
        val all = device.apps(); val preferred = graph.settings.get("music_package")
        all.singleOrNull { it.id == preferred }?.let { return it }
        val foreground = previousPackage ?: graph.uiAutomation.foregroundPackage()
        val candidates = device.audioApps()
        candidates.singleOrNull { it.id == foreground }?.let { return it }
        return graph.uiAutomation.chooseApps(candidates, "음악 앱이 여러 개입니다. 어느 앱에서 수행할까요?")
            ?: throw UiUnavailable(InvocationState.EXECUTION_FAILED, "활성 음악 세션과 설치된 음악 앱에서 실행할 앱을 찾지 못했습니다.")
    }
    private fun blocked(state: InvocationState, message: String) = PreparedRequest(ToolPlan(emptyList(), message),
        PolicyDecision.Blocked(message), AgentFailure(state, message))
}
