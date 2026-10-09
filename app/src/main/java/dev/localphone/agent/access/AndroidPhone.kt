package dev.localphone.agent.access

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import dev.localphone.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Bridges the core agent to the accessibility service, launcher and audio system. */
class AndroidPhone(private val context: Context) : Phone {
    private val apps = AppIndex(context)
    private val service get() = AgentAccessibilityService.instance

    // Reading another app's tree is blocking IPC; keep it off the main thread (the overlay lives there).
    override suspend fun observe(): Snapshot? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { service?.observe() }

    override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
        val s = service ?: return false
        val snap = view.snapshot
        fun node(id: Int) = view.element(id)?.node
        return when (action) {
            is AgentAction.Click -> node(action.id)?.let { s.click(snap, it, long = false) } ?: false
            is AgentAction.LongClick -> node(action.id)?.let { s.click(snap, it, long = true) } ?: false
            is AgentAction.Type -> node(action.id)?.let { s.setText(snap, it, action.text, action.enter) } ?: false
            is AgentAction.Scroll -> s.scroll(snap, action.id?.let(::node) ?: view.lists.maxByOrNull { it.bounds.height * it.bounds.width }?.node, action.dir)
            AgentAction.Back -> s.back()
            is AgentAction.TapEnd -> node(action.id)?.let { s.tapObserved(snap, it, trailing = true) } ?: false
            else -> false
        }
    }

    override suspend fun tap(view: ScreenView, id: Int): Boolean = view.element(id)?.let {
        service?.tapObserved(view.snapshot, it.node)
    } ?: false

    override fun matchesApp(view: ScreenView, name: String): Boolean =
        (apps.find(name, allowFuzzy = false) as? AppIndex.Match.Found)?.app?.packageName == view.snapshot.packageName

    override suspend fun openApp(name: String): OpenAppResult = when (val match = apps.find(name)) {
        is AppIndex.Match.Found -> {
            // Already in front (spoken "유튜브" vs label "YouTube"): do not restart it.
            if (observe()?.packageName == match.app.packageName && match.app.space.isEmpty())
                OpenAppResult(true, "$ALREADY_OPEN: ${match.app.display}")
            else if (apps.launch(match.app)) {
                val confirmed = withTimeoutOrNull(5000) {
                    while (observe()?.packageName != match.app.packageName) delay(100)
                    true
                } == true
                OpenAppResult(confirmed, if (confirmed) "열림: ${match.app.display}" else "실행 요청 후 전면 화면을 확인하지 못함: ${match.app.display}")
            }
            else OpenAppResult(false, "'${match.app.display}' 실행 실패")
        }
        is AppIndex.Match.Missing -> inSecureFolder(name) ?: run {
            // Samsung Secure Folder apps are not listed when the OS hides that profile from launchers.
            val secureFolder = runCatching { context.packageManager.getPackageInfo("com.samsung.knox.securefolder", 0) }.isSuccess
            OpenAppResult(false, "'$name' 앱을 찾지 못함. 비슷한 앱: " + match.similar.joinToString(", ") { it.display } +
                if (secureFolder) ". 보안 폴더 안의 앱이면 open_app \"보안 폴더\"로 연 뒤 그 안에서 누른다" else "")
        }
    }

    /**
     * Apps inside Samsung Secure Folder are invisible to this profile's launcher APIs: open Secure
     * Folder and tap the icon whose name matches ("클립스트림" → "Clipstream Player"). Null when
     * Secure Folder is locked or has no such app, so the screen agent takes over (and asks to unlock).
     */
    private suspend fun inSecureFolder(name: String): OpenAppResult? {
        fun matches(label: String) = GoalText.normalize(label).let { it.isNotEmpty() && it.contains(GoalText.normalize(name)) } ||
            GoalText.soundsLike(name, label)
        observe()?.takeIf { !it.home && matches(it.appLabel) }?.let { return OpenAppResult(true, "$ALREADY_OPEN: ${it.appLabel}") }
        val folder = (apps.find("보안 폴더", allowFuzzy = false) as? AppIndex.Match.Found)?.app ?: return null
        if (!apps.launch(folder)) return null
        val (view, icon) = withTimeoutOrNull(4000) {
            while (true) {
                val view = observe()?.let(ScreenCompactor::compact)
                if (view != null && view.snapshot.packageName == folder.packageName)
                    view.elements.firstOrNull { it.enabled && matches(it.label) }?.let { return@withTimeoutOrNull view to it }
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: return null
        if (!perform(view, AgentAction.Click(icon.id))) return null
        val opened = withTimeoutOrNull(4500) {
            while (true) {
                val now = observe()
                if (now != null && now.packageName != folder.packageName && matches(now.appLabel)) return@withTimeoutOrNull true
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true
        android.util.Log.i("AgentTools", "secure folder open '$name' via '${icon.label}' opened=$opened")
        return if (opened) OpenAppResult(true, "열림: ${icon.label} (보안 폴더)") else null
    }

    override suspend fun media(key: MediaKey): Boolean {
        val code = when (key) {
            MediaKey.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaKey.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaKey.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKey.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return true
    }

    override suspend fun leaveUnreadable(): Boolean {
        val s = service ?: return false
        for (step in 0..1) {
            if (s.observationDiagnostic?.startsWith("NO_ROOT") != true) return step > 0
            if (step == 0) s.back() else s.home()
            delay(700)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { s.observe() }
        }
        return s.observationDiagnostic?.startsWith("NO_ROOT") != true
    }

    override fun musicActive(): Boolean? = runCatching { context.getSystemService(AudioManager::class.java).isMusicActive }.getOrNull()

    override fun now(): Long = SystemClock.elapsedRealtime()
}

/** Prefix the core agent recognizes as "nothing launched, the app is already on screen". */
const val ALREADY_OPEN = "이미 열려 있음"
