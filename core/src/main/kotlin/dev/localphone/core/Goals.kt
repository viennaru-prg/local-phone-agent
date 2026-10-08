package dev.localphone.core

/** The original user goal survives an unavailable structured tool. No coordinates or packages are guessed. */
object GoalRequests {
    private val appTask = Regex("^(.+?)(?:\\s*앱)?에서\\s*(.+)$")
    fun fallback(utterance: String): ToolPlan {
        CommandSafety.blockedReason(utterance)?.let { return ToolPlan(emptyList(), it) }
        val text = utterance.trim().trimEnd('.', '。', '!')
        val match = appTask.matchEntire(text)
        val app = match?.groupValues?.get(1)?.trim()
            ?: if (CommandSafety.needsScreenGoal(text)) "지도" else ""
        val goal = match?.groupValues?.get(2)?.trim() ?: text
        if (app.length > 80 || goal.isBlank() || goal.length > 1000 || text.any(Char::isISOControl))
            return ToolPlan(emptyList(), "작업 목표를 확인하지 못했습니다.")
        return ToolPlan(listOf(Action.AppTask(app, goal)))
    }
}

/** Ephemeral, bounded accessibility observations. Tokens refer to this observation, never screen coordinates. */
data class UiNode(val token: String, val label: String, val viewId: String = "", val role: String = "",
                  val clickable: Boolean = false, val editable: Boolean = false, val scrollable: Boolean = false,
                  val parent: String? = null, val fingerprint: String = "", val enabled: Boolean = true,
                  val clickAction: Boolean = false, val tapEligible: Boolean = false) {
    val canClick get() = clickable || clickAction || tapEligible
}
data class UiScreen(val packageName: String, val revision: Long, val nodes: List<UiNode>) {
    fun node(token: String) = nodes.singleOrNull { it.token == token }
    fun exact(vararg labels: String): List<UiNode> {
        val wanted = labels.map(PlaceText::normalize)
        return nodes.filter { !it.editable && it.label.split(" · ").any { value -> PlaceText.normalize(value) in wanted } }
    }
    fun text() = nodes.joinToString("\n") { it.label }
    fun clickTarget(node: UiNode): UiNode {
        var current = node
        repeat(8) {
            if (current.canClick) return current
            current = node(current.parent ?: return node) ?: return node
        }
        return node
    }
    fun descendants(token: String): List<UiNode> = nodes.filter { candidate ->
        var parent = candidate.parent
        repeat(12) {
            if (parent == token) return@filter true
            parent = node(parent ?: return@filter false)?.parent
        }
        false
    }
}
object UiFingerprint {
    fun describe(screen: UiScreen, node: UiNode): String {
        val values = (listOf(node) + screen.descendants(node.token)).take(41)
        val shape = values.joinToString("\u0000") { "${it.role}\u0001${it.viewId}\u0001${it.label}\u0001${it.clickable}:${it.editable}:${it.scrollable}:${it.enabled}:${it.clickAction}:${it.tapEligible}" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(shape.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
sealed interface UiCommand {
    data class Click(val token: String, val gesture: Boolean = false) : UiCommand
    data class SetText(val token: String, val text: String) : UiCommand
    data class Submit(val token: String) : UiCommand
    data class Scroll(val token: String) : UiCommand
    data object Back : UiCommand
}
sealed interface UiProposal {
    data class Act(val command: UiCommand) : UiProposal
    data class Complete(val evidenceToken: String) : UiProposal
    data class Ambiguous(val prompt: String, val tokens: List<String>) : UiProposal
    data object Unavailable : UiProposal
}

/** Screen text is untrusted data; it cannot grant authority beyond the command. */
object UiGrounding {
    private val sensitive = Regex("비밀번호|인증번호|접근성.*허용|권한.*허용|결제|송금|계정삭제|전체삭제|password|payment|transfer", RegexOption.IGNORE_CASE)
    private val destructive = Regex("삭제|초기화|해지|로그아웃|delete|reset|unsubscribe|logout", RegexOption.IGNORE_CASE)
    fun allowed(goal: String, screen: UiScreen, command: UiCommand): Boolean {
        if (command == UiCommand.Back) return true
        val token = when (command) {
            is UiCommand.Click -> command.token
            is UiCommand.SetText -> command.token
            is UiCommand.Submit -> command.token
            is UiCommand.Scroll -> command.token
            UiCommand.Back -> return true
        }
        val node = screen.node(token) ?: return false
        if (!node.enabled || command is UiCommand.Click && !screen.clickTarget(node).enabled) return false
        if (sensitive.containsMatchIn(PlaceText.normalize(node.label))) return false
        if (destructive.containsMatchIn(node.label) && !destructive.containsMatchIn(goal)) return false
        return when (command) {
            is UiCommand.SetText -> node.editable && command.text.length in 1..500 &&
                PlaceText.normalize(goal).contains(PlaceText.normalize(command.text)) && !command.text.any(Char::isISOControl)
            is UiCommand.Submit -> node.editable
            is UiCommand.Scroll -> node.scrollable
            is UiCommand.Click -> screen.clickTarget(node).canClick &&
                (node.label.isNotBlank() || screen.descendants(node.token).any { it.label.isNotBlank() })
            UiCommand.Back -> true
        }
    }
}

/** A label-driven path for explicit UI requests; a local model may propose further observed-node steps. */
object SemanticUi {
    // Only observed start controls, including the provider's countdown suffix. Do not
    // treat explanatory prose or an end/cancel control as a request to start guidance.
    private const val START = "(?:안내시작|주행시작|경로안내시작|내비게이션시작|내비시작|startnavigation|startguidance)"
    private val navigationStart = Regex("^$START(?:\\d+(?:초|s|sec|seconds)?(?:후)?(?:자동(?:으로)?(?:안내)?시작)?)?$", RegexOption.IGNORE_CASE)
    private val delayedNavigationStart = Regex("^(?:\\d+(?:초|s|sec|seconds)후)(?:자동(?:으로)?)?$START$", RegexOption.IGNORE_CASE)
    fun navigationStartLabel(label: String): Boolean = label.split(" · ", "\n").any {
        val value = PlaceText.normalize(it)
        navigationStart.matches(value) || delayedNavigationStart.matches(value)
    }
    fun navigationStartTargets(screen: UiScreen): List<UiNode> = screen.nodes.filter { node ->
        !node.editable && navigationStartLabel(node.label)
    }.map(screen::clickTarget).filter { it.canClick }.distinctBy { it.token }
    fun navigationPreview(screen: UiScreen): Boolean = screen.nodes.any { navigationStartLabel(it.label) } ||
        screen.exact("자동차 경로", "경로 정보", "추천 경로", "경로 옵션").isNotEmpty()
    /** Only explicitly labelled destination fields can contradict an already observed route. */
    fun destinationLabels(screen: UiScreen): List<String> = screen.nodes.flatMap { node ->
        node.label.split(" · ", "\n").mapNotNull { label ->
            Regex("^(?:목적지|도착지|destination)\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
                .matchEntire(label.trim())?.groupValues?.get(1)
        }
    }
    fun clickLabel(goal: String): String? = Regex("^(.+?)(?:을|를)?\\s*(?:눌러(?:줘| 줘)?|클릭(?:해줘)?|선택(?:해줘)?|열어(?:줘| 줘)?)$")
        .matchEntire(goal.trim())?.groupValues?.get(1)?.trim()?.removeSuffix(" 버튼")?.trim()
    fun searchQuery(goal: String): String? = Regex("^(.+?)(?:을|를)?\\s*검색(?:해줘|해 줘|해|해봐|해 봐)$")
        .matchEntire(goal.trim())?.groupValues?.get(1)?.trim()
    fun matches(screen: UiScreen, label: String): List<UiNode> = screen.exact(label).map(screen::clickTarget).distinctBy { it.token }
    fun navigationStarted(screen: UiScreen): Boolean {
        val text = PlaceText.normalize(screen.text())
        if (screen.nodes.any { navigationStartLabel(it.label) }) return false
        val end = listOf("안내종료", "주행종료", "내비게이션종료", "경로종료", "stopnavigation", "endnavigation").any(text::contains)
        val status = listOf("안내중", "주행중", "남은거리", "도착예정", "remaining", "navigationactive").any(text::contains)
        if (end && status) return true
        // The driving page may hide End behind a menu. Speed + route telemetry + a
        // driving control/instruction are independent active-guidance observations.
        val raw = screen.text()
        val speed = Regex("\\d+(?:[.,]\\d+)?\\s*(?:km/h|㎞/h|kmh)", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val distance = Regex("\\d+(?:[.,]\\d+)?\\s*(?:km|㎞|m)(?![a-z/])", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val arrival = Regex("도착\\s*(?:예정|시간)|\\d{1,2}:\\d{2}|\\d+\\s*분").containsMatchIn(raw)
        val driving = listOf("음성안내", "안내음성", "경로재탐색", "경로재검색", "전체경로", "좌회전", "우회전", "유턴").any(text::contains)
        return speed && distance && arrival && (end || driving)
    }
}
