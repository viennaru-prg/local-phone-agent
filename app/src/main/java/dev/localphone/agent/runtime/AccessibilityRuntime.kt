package dev.localphone.agent.runtime

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.localphone.agent.BuildConfig
import dev.localphone.agent.data.SecureSettings
import dev.localphone.core.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Implemented by the OS-bound service, never by an exported command receiver. */
interface UiAccess {
    fun foregroundPackage(): String?
    fun begin(id: String, packageName: String, cancel: () -> Unit)
    fun end(id: String)
    fun screen(id: String): UiScreen?
    suspend fun perform(id: String, screen: UiScreen, command: UiCommand): Boolean
    suspend fun choose(id: String, prompt: String, choices: List<String>): Int?
    fun progress(id: String, message: String)
}

class UiUnavailable(val state: InvocationState, override val message: String) : Exception(message)

/** User-enabled accessibility is a runtime capability; per-app selectors/settings are optional. */
class AccessibilityRuntime(private val context: Context, private val settings: SecureSettings) {
    private val mutex = Mutex()
    @Volatile internal var access: UiAccess? = null
    val available get() = BuildConfig.UI_AUTOMATION_AVAILABLE && settings.get("ui_automation_consent") == "yes" && access != null
    val active get() = activeSession != null
    @Volatile private var activeSession: String? = null
    fun foregroundPackage(): String? = if (available) access?.foregroundPackage() else null
    fun settingsIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun requireAvailable() {
        if (!BuildConfig.UI_AUTOMATION_AVAILABLE) throw UiUnavailable(InvocationState.PERMISSION_REQUIRED,
            "이 설치판에 화면 작업 기능이 없습니다. 화면 작업판 APK를 사용해 주세요.")
        if (!available) throw UiUnavailable(InvocationState.PERMISSION_REQUIRED,
            "공식 연동으로 완료하지 못했습니다. 설정의 '화면 작업 연결'에서 접근성 서비스를 한 번 허용하면 앱 화면에서 계속 수행할 수 있습니다.")
        if (!ProfileScope(context).canAct()) throw UiUnavailable(InvocationState.AUTH_REQUIRED, "잠금 해제 후 다시 호출해 주세요.")
    }
    suspend fun chooseApps(candidates: List<AppCandidate>, prompt: String): AppCandidate? = mutex.withLock {
        if (candidates.size == 1) return@withLock candidates.single()
        if (candidates.isEmpty()) return@withLock null
        requireAvailable()
        val port = access ?: return@withLock null
        val id = UUID.randomUUID().toString(); val job = currentCoroutineContext().job
        activeSession = id
        try {
            port.begin(id, "") { job.cancel(CancellationException("앱 선택을 취소했습니다.")) }
            val index = withTimeoutOrNull(30000) { port.choose(id, prompt, candidates.map { it.name }) }
                ?: throw UiUnavailable(InvocationState.AMBIGUOUS, "실행 가능한 앱이 여러 개입니다. 앱을 선택하지 않아 중단했습니다.")
            candidates.getOrNull(index)
        } finally { try { port.end(id) } finally { activeSession = null } }
    }
    suspend fun <T> run(packageName: String, goal: String, body: suspend UiSession.() -> T): T = mutex.withLock {
        requireAvailable()
        val port = access ?: throw UiUnavailable(InvocationState.PERMISSION_REQUIRED, "화면 작업 서비스가 연결되지 않았습니다.")
        val id = UUID.randomUUID().toString()
        val job = currentCoroutineContext().job
        activeSession = id
        try {
            port.begin(id, packageName) { job.cancel(CancellationException("사용자가 화면 작업을 취소했습니다.")) }
            // This includes local-model prefill and several observed UI steps. A 45-second
            // whole-task deadline could cancel a healthy Qwen midway through the next screen.
            // Individual native inference has its own bound; action/repetition limits remain.
            withTimeout(180000) { UiSession(id, goal, port, context).body() }
        } finally { try { port.end(id) } finally { activeSession = null } }
    }
}

class UiSession(private val id: String, val goal: String, private val port: UiAccess, private val context: Context) {
    private var steps = 0
    private val attempts = mutableMapOf<String, Int>()
    var observedScreen: UiScreen? = null
        private set
    private fun authorized() {
        if (!ProfileScope(context).canAct()) throw UiUnavailable(InvocationState.AUTH_REQUIRED, "잠금으로 화면 작업을 중단했습니다.")
    }
    suspend fun screen(): UiScreen {
        repeat(32) {
            currentCoroutineContext().ensureActive(); authorized()
            port.screen(id)?.let { observedScreen = it; return it }
            delay(180)
        }
        throw UiUnavailable(InvocationState.EXECUTION_FAILED, "앱 화면을 읽지 못했거나 사용자가 다른 화면으로 이동했습니다.")
    }
    suspend fun act(screen: UiScreen, command: UiCommand): Boolean {
        currentCoroutineContext().ensureActive(); authorized()
        val token = when (command) {
            is UiCommand.Click -> command.token
            is UiCommand.SetText -> command.token
            is UiCommand.Submit -> command.token
            is UiCommand.Scroll -> command.token
            UiCommand.Back -> null
        }
        val requested = token?.let(screen::node)
        // Waiting for an observed disabled control is not an action or a policy violation.
        if (requested != null && (!requested.enabled || command is UiCommand.Click && !screen.clickTarget(requested).enabled)) return false
        if (++steps > 28) throw UiUnavailable(InvocationState.EXECUTION_FAILED, "화면 작업 횟수를 초과했습니다. 완료 여부를 확인할 수 없습니다.")
        if (!UiGrounding.allowed(goal, screen, command)) throw UiUnavailable(InvocationState.POLICY_BLOCKED, "요청 범위를 벗어나거나 보호된 화면이어서 조작하지 않았습니다.")
        val key = "$command:${screen.text().hashCode()}"
        val count = attempts.getOrDefault(key, 0) + 1; attempts[key] = count
        if (count > 2) return false
        val performed = port.perform(id, screen, command)
        if (performed) {
            // Continue as soon as the target's observed contents change; a fixed sleep after
            // every button adds needless latency. Unchanged/animating controls still settle.
            repeat(6) {
                delay(50); currentCoroutineContext().ensureActive(); authorized()
                val refreshed = port.screen(id)
                if (refreshed != null && refreshed.text() != screen.text()) return true
            }
        }
        return performed
    }
    suspend fun select(screen: UiScreen, nodes: List<UiNode>, prompt: String, forceChoice: Boolean = false): UiNode? {
        if (nodes.isEmpty()) return null
        if (nodes.size == 1 && !forceChoice) return nodes.single()
        val descriptions = nodes.map { node -> (listOf(node.label) + screen.descendants(node.token).map { it.label })
            .filter(String::isNotBlank).distinct().joinToString(" · ").take(300) }
        val index = withTimeoutOrNull(30000) { port.choose(id, prompt, descriptions) }
            ?: throw UiUnavailable(InvocationState.AMBIGUOUS, "화면에서 여러 후보를 발견했습니다. 목적지를 선택하지 않아 중단했습니다.")
        return nodes.getOrNull(index)
    }
    fun progress(message: String) { port.progress(id, message) }
}
