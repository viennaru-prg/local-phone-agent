package dev.localphone.agent.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.localphone.agent.AgentApplication
import dev.localphone.core.*
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/** Reads only the commanded app during a live user request. No event text, screenshots, or background history. */
class AgentAccessibilityService : AccessibilityService(), UiAccess {
    private val graph get() = application as AgentApplication
    private var session: String? = null
    private var target: String? = null
    private var cancelAction: (() -> Unit)? = null
    private var sequence = 0L
    private var lastScreen: UiScreen? = null
    private var overlay: LinearLayout? = null
    private var progressLabel: TextView? = null
    private var choice: CancellableContinuation<Int?>? = null
    private val windowManager get() = getSystemService(WindowManager::class.java)

    override fun onServiceConnected() { restrictEvents(packageName); graph.uiAutomation.access = this }
    private fun restrictEvents(packageName: String) {
        val info = serviceInfo ?: return
        info.packageNames = arrayOf(packageName); serviceInfo = info
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Contents are read on demand, not from events. */ }
    override fun onInterrupt() { cancelAction?.invoke(); clear() }
    override fun onUnbind(intent: Intent?): Boolean {
        if (graph.uiAutomation.access === this) graph.uiAutomation.access = null
        cancelAction?.invoke(); clear(); return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (graph.uiAutomation.access === this) graph.uiAutomation.access = null
        cancelAction?.invoke(); clear(); super.onDestroy()
    }
    private fun local(packageName: String): Boolean = runCatching {
        UserHandle.getUserHandleForUid(packageManager.getApplicationInfo(packageName, 0).uid) == Process.myUserHandle()
    }.getOrDefault(false)

    override fun foregroundPackage(): String? {
        if (!ProfileScope(this).canAct()) return null
        val root = rootInActiveWindow ?: return null
        try { return root.packageName?.toString()?.takeIf { it != packageName && local(it) } }
        finally { release(root) }
    }
    override fun begin(id: String, packageName: String, cancel: () -> Unit) {
        check(session == null && (packageName.isEmpty() || local(packageName) && packageName != this.packageName))
        session = id; target = packageName; cancelAction = cancel
        restrictEvents(packageName.ifBlank { this.packageName })
        showProgress("앱 화면에서 처리 중…")
    }
    override fun end(id: String) { if (session == id) clear() }
    private fun clear() {
        session = null; target = null; lastScreen = null; cancelAction = null
        restrictEvents(packageName)
        choice?.cancel(); choice = null
        removeOverlay()
    }

    private fun activeRoot(): AccessibilityNodeInfo? {
        if (session == null || !ProfileScope(this).canAct()) return null
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == target) return active
        val activePackage = active?.packageName?.toString()
        val invocationCover = activePackage == packageName && active?.findAccessibilityNodeInfosByViewId("$packageName:id/invocation_status")
            .orEmpty().let { found -> found.forEach(::release); found.isNotEmpty() }
        release(active)
        // An IME or our accessibility overlay can have focus over the target. Never click through another app/dialog.
        val all = windows
        val allowedCover = invocationCover || all.any { it.isActive &&
            it.type in listOf(AccessibilityWindowInfo.TYPE_INPUT_METHOD, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) }
        if (!allowedCover) return null
        for (window in all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (invocationCover || it.isActive || it.isFocused) }) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == target) return root
            release(root)
        }
        return null
    }
    private fun label(node: AccessibilityNodeInfo): String = listOfNotNull(node.text?.toString(), node.contentDescription?.toString(),
        if (node.isEditable) node.hintText?.toString() else null).filter(String::isNotBlank).distinct().joinToString(" · ").take(400)
    private fun clickAction(node: AccessibilityNodeInfo) = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    private fun tapEligible(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable || !SemanticUi.navigationStartLabel(label(node))) return false
        val role = node.className?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        if (!role.endsWith("Button") && !Regex("(?:btn|button).*?(?:start|guide|navi)|(?:start|guide).*?(?:btn|button)", RegexOption.IGNORE_CASE).containsMatchIn(id)) return false
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val display = resources.displayMetrics
        // A semantic button's own observed bounds, never a root container or a guessed coordinate.
        return !bounds.isEmpty && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= display.widthPixels &&
            bounds.bottom <= display.heightPixels && bounds.height() <= display.heightPixels / 3
    }

    override fun screen(id: String): UiScreen? {
        if (session != id || graph.settings.get("ui_automation_consent") != "yes") return null
        val root = activeRoot() ?: return null
        val nodes = mutableListOf<UiNode>()
        fun visit(node: AccessibilityNodeInfo, token: String, parent: String?, depth: Int) {
            if (depth > 28 || nodes.size >= 384 || node.isPassword || !node.isVisibleToUser) return
            if (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) return
            if (node.packageName?.toString() != target) return
            nodes += UiNode(token, label(node), node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
                node.isClickable, node.isEditable, node.isScrollable, parent, enabled = node.isEnabled,
                clickAction = clickAction(node), tapEligible = tapEligible(node))
            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                try { visit(child, "$token.$index", token, depth + 1) } finally { release(child) }
            }
        }
        try { visit(root, "r", null, 0) } finally { release(root) }
        if (nodes.isEmpty()) return null
        val observed = UiScreen(target ?: return null, ++sequence, nodes)
        return observed.copy(nodes = nodes.map { it.copy(fingerprint = UiFingerprint.describe(observed, it)) }).also { lastScreen = it }
    }
    private fun find(root: AccessibilityNodeInfo, token: String): AccessibilityNodeInfo? {
        if (!token.matches(Regex("r(?:\\.\\d+){0,28}"))) return null
        @Suppress("DEPRECATION") var current = AccessibilityNodeInfo.obtain(root)
        for (index in token.split('.').drop(1)) {
            val childIndex = index.toInt()
            val next = if (childIndex < current.childCount) runCatching { current.getChild(childIndex) }.getOrNull() else null
            release(current)
            current = next ?: return null
        }
        return current
    }
    override suspend fun perform(id: String, screen: UiScreen, command: UiCommand): Boolean {
        currentCoroutineContext().ensureActive()
        if (session != id || lastScreen?.revision != screen.revision || screen.packageName != target ||
            graph.settings.get("ui_automation_consent") != "yes") return false
        val root = activeRoot() ?: return false
        try {
            if (command == UiCommand.Back) return performGlobalAction(GLOBAL_ACTION_BACK)
            val token = when (command) {
                is UiCommand.Click -> command.token
                is UiCommand.SetText -> command.token
                is UiCommand.Submit -> command.token
                is UiCommand.Scroll -> command.token
                UiCommand.Back -> return false
            }
            val requested = screen.node(token) ?: return false
            val expected = if (command is UiCommand.Click) screen.clickTarget(requested) else requested
            val node = find(root, expected.token) ?: return false
            try {
                // Revalidate the observed element on the live tree. Never reuse a stale node handle.
                if (!node.isVisibleToUser || !node.isEnabled || node.isPassword || node.packageName?.toString() != target ||
                    label(node) != expected.label || node.viewIdResourceName.orEmpty() != expected.viewId ||
                    node.className?.toString().orEmpty() != expected.role) return false
                if (expected.fingerprint.isNotBlank() && liveFingerprint(node) != expected.fingerprint) return false
                return when (command) {
                    is UiCommand.SetText -> {
                        if (!node.isEditable) false else {
                            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command.text) })
                        }
                    }
                    is UiCommand.Submit -> android.os.Build.VERSION.SDK_INT >= 30 &&
                        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    is UiCommand.Scroll -> node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    is UiCommand.Click -> {
                        if (!(node.isClickable || clickAction(node) || tapEligible(node))) false
                        else if (!command.gesture && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) true
                        else tapObservedNode(id, node)
                    }
                    UiCommand.Back -> false
                }
            } finally { release(node) }
        } finally { release(root) }
    }
    private fun liveFingerprint(root: AccessibilityNodeInfo): String {
        val values = mutableListOf<UiNode>()
        fun visit(node: AccessibilityNodeInfo, token: String, parent: String?, depth: Int) {
            if (depth > 28 || values.size >= 41 || !node.isVisibleToUser || node.isPassword || node.packageName?.toString() != target) return
            if (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) return
            values += UiNode(token, label(node), node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
                node.isClickable, node.isEditable, node.isScrollable, parent, enabled = node.isEnabled,
                clickAction = clickAction(node), tapEligible = tapEligible(node))
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                try { visit(child, "$token.$i", token, depth + 1) } finally { release(child) }
            }
        }
        visit(root, "r", null, 0)
        val screen = UiScreen(target.orEmpty(), 0, values)
        return values.firstOrNull()?.let { UiFingerprint.describe(screen, it) }.orEmpty()
    }
    private suspend fun tapObservedNode(id: String, node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || session != id) return false
        overlay?.let { view ->
            val position = IntArray(2); view.getLocationOnScreen(position)
            if (Rect(position[0], position[1], position[0] + view.width, position[1] + view.height).contains(bounds.centerX(), bounds.centerY())) return false
        }
        val display = resources.displayMetrics
        if (bounds.left < 0 || bounds.top < 0 || bounds.right > display.widthPixels || bounds.bottom > display.heightPixels) return false
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(
            Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }, 0, 70)).build()
        return suspendCancellableCoroutine { continuation ->
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(session == id) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }
    override fun progress(id: String, message: String) { if (session == id && choice == null) progressLabel?.text = message }
    override suspend fun choose(id: String, prompt: String, choices: List<String>): Int? {
        if (session != id) return null
        return suspendCancellableCoroutine { continuation ->
            choice = continuation
            removeOverlay()
            val panel = panel()
            panel.addView(TextView(this).apply { text = prompt; textSize = 15f; setTextColor(Color.BLACK) })
            choices.take(8).forEachIndexed { index, description ->
                panel.addView(Button(this).apply {
                    text = description; isAllCaps = false
                    setOnClickListener {
                        if (session == id && continuation.isActive) {
                            choice = null; showProgress("선택한 항목에서 계속 처리 중…"); continuation.resume(index)
                        }
                    }
                })
            }
            panel.addView(Button(this).apply { text = "취소"; setOnClickListener { cancelAction?.invoke() } })
            attachOverlay(panel)
            continuation.invokeOnCancellation { if (choice === continuation) choice = null }
        }
    }
    private fun showProgress(message: String) {
        removeOverlay()
        val panel = panel()
        progressLabel = TextView(this).apply { text = message; textSize = 14f; setTextColor(Color.BLACK) }
        panel.addView(progressLabel)
        panel.addView(Button(this).apply { text = "작업 취소"; setOnClickListener { cancelAction?.invoke() } })
        attachOverlay(panel)
    }
    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(6))
        background = GradientDrawable().apply { setColor(Color.rgb(249, 249, 252)); cornerRadius = dp(18).toFloat() }
    }
    private fun attachOverlay(panel: LinearLayout) {
        val params = WindowManager.LayoutParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(54) }
        windowManager.addView(panel, params); overlay = panel
    }
    private fun removeOverlay() { overlay?.let { runCatching { windowManager.removeViewImmediate(it) } }; overlay = null; progressLabel = null }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    @Suppress("DEPRECATION") private fun release(node: AccessibilityNodeInfo?) { node?.recycle() }
}
