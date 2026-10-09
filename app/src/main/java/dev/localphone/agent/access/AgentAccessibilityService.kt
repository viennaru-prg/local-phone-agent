package dev.localphone.agent.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.localphone.core.Bounds
import dev.localphone.core.RawNode
import dev.localphone.core.ScrollDir
import dev.localphone.core.ScrollGesture
import dev.localphone.core.Snapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads the top app window on demand and performs actions on it. Nothing is collected from events;
 * the tree is read only while a command is running. Windows of other profiles (e.g. Secure Folder)
 * are included when the OS exposes them to this service.
 */
class AgentAccessibilityService : AccessibilityService() {
    private var overlay: LinearLayout? = null
    private var status: TextView? = null
    var onCancel: (() -> Unit)? = null
    @Volatile var observationDiagnostic: String = "not observed"; private set

    // Window id of recent observations, used to find the node an action targets on the live screen.
    private val windowOf = java.util.Collections.synchronizedMap(object : java.util.IdentityHashMap<Snapshot, Int>() {})
    private val recent = ArrayDeque<Snapshot>()

    override fun onServiceConnected() { instance = this; Log.i(TAG, "connected") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { hideOverlay(); if (instance === this) instance = null; super.onDestroy() }

    // ---------- observation ----------

    private fun targetWindow(): AccessibilityWindowInfo? = windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive || it.isFocused }.thenByDescending { it.layer })
        .firstOrNull { w -> w.root?.let { r -> val pkg = r.packageName?.toString(); r.recycleCompat(); pkg != packageName } ?: false }

    fun observe(): Snapshot? {
        // A service observing only window-state events can retain the previous app's window tree
        // after a launch. On the S25 this kept returning Yogiyo while NAVER Map was in front.
        // Invalidate on demand as well as subscribing to window/content events on older Android.
        if (android.os.Build.VERSION.SDK_INT >= 33) clearCache()
        val window = targetWindow()
        val root = window?.root ?: rootInActiveWindow
        if (root == null) {
            val summary = windows.joinToString { "${it.id}:${it.type}:active=${it.isActive}:focused=${it.isFocused}" }
            observationDiagnostic = "NO_ROOT windows=$summary"
            Log.w(TAG, observationDiagnostic)
            return null
        }
        val pkg = root.packageName?.toString().orEmpty()
        val rootWindowId = root.windowId
        if (pkg == packageName) { root.recycleCompat(); observationDiagnostic = "OWN_APP_FOREGROUND"; return null }
        val display = resources.displayMetrics
        val nodes = mutableListOf<RawNode>()
        // Each child read is a call into the other app; a busy app (NAVER Map starting up) answers slowly.
        // Stop after a time budget and use what was read.
        val deadline = android.os.SystemClock.uptimeMillis() + 2500
        fun visit(node: AccessibilityNodeInfo, path: String, parent: Int, depth: Int) {
            if (depth > 40 || nodes.size >= 700 || !node.isVisibleToUser || node.isPassword) return
            if (android.os.SystemClock.uptimeMillis() > deadline) return
            val rect = Rect(); node.getBoundsInScreen(rect)
            val index = nodes.size
            nodes += RawNode(path, parent,
                text = node.text?.toString().orEmpty(),
                desc = node.contentDescription?.toString().orEmpty(),
                hint = if (node.isEditable) node.hintText?.toString().orEmpty() else "",
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                clickable = node.isClickable, longClickable = node.isLongClickable, editable = node.isEditable,
                scrollable = node.isScrollable, checkable = node.isCheckable, checked = node.isChecked,
                selected = node.isSelected, enabled = node.isEnabled,
                bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom))
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                try { visit(child, "$path.$i", index, depth + 1) } finally { child.recycleCompat() }
            }
        }
        try { visit(root, "r", -1, 0) } finally { root.recycleCompat() }
        if (nodes.isEmpty()) {
            observationDiagnostic = "EMPTY_TREE package=$pkg window=${window?.id}"
            Log.w(TAG, observationDiagnostic)
            return null
        }
        observationDiagnostic = "OK package=$pkg window=${window?.id} nodes=${nodes.size}"
        // Apps in another profile (Secure Folder, user 150) are not visible to this user's PackageManager;
        // the window title ("Clipstream Player") is the name the user sees.
        val label = appLabel(pkg).takeIf { it != pkg } ?: window?.title?.toString()?.takeIf { it.isNotBlank() } ?: pkg
        val snapshot = Snapshot(pkg, label, nodes, display.widthPixels, display.heightPixels, home = pkg == launcherPackage)
        synchronized(recent) {
            recent.addLast(snapshot); windowOf[snapshot] = window?.id ?: rootWindowId
            while (recent.size > 6) windowOf.remove(recent.removeFirst())
        }
        return snapshot
    }

    private val launcherPackage: String? by lazy {
        packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * Waits until this app's own voice sheet has left the screen. While it is on top the system lists
     * only its window, so the first action of a run could not find its node again (23% of runs failed
     * their first tap and had to retry).
     */
    suspend fun awaitOwnWindowGone(timeoutMs: Long = 1500): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (!ownWindowShowing()) return true
            kotlinx.coroutines.delay(50)
        }
        Log.w(TAG, "own window still showing after ${timeoutMs}ms")
        return false
    }

    private val voiceTitle: String? by lazy {
        runCatching { packageManager.getActivityInfo(android.content.ComponentName(this, dev.localphone.agent.VoiceActivity::class.java), 0).loadLabel(packageManager).toString() }.getOrNull()
    }

    /** This app's voice sheet is an application window on screen (by package, or by its title when the root is hidden). */
    fun ownWindowShowing(): Boolean = runCatching {
        windows.any { w -> w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            (w.root?.packageName?.toString() == packageName || (voiceTitle != null && w.title?.toString() == voiceTitle)) }
    }.getOrDefault(false)

    /** Re-finds the observed node in the live tree and checks it is still the same element. */
    private fun live(snapshot: Snapshot, index: Int): AccessibilityNodeInfo? {
        val expected = snapshot.nodes.getOrNull(index) ?: return null
        val windowId = windowOf[snapshot] ?: return null.also { Log.w(TAG, "live: snapshot has no window") }
        if (android.os.Build.VERSION.SDK_INT >= 33) clearCache()
        val root = windows.firstOrNull { it.id == windowId }?.root
            ?: return null.also { Log.w(TAG, "live: window $windowId gone; windows=${windows.joinToString { "${it.id}:${it.type}:${it.title}" }}") }
        fun same(n: AccessibilityNodeInfo) = n.className?.toString().orEmpty() == expected.className &&
            n.text?.toString().orEmpty() == expected.text && n.contentDescription?.toString().orEmpty() == expected.desc
        var current: AccessibilityNodeInfo? = root
        for (part in expected.path.split('.').drop(1)) {
            current = runCatching { current?.getChild(part.toInt()) }.getOrNull()
            if (current == null) break
        }
        current?.takeIf(::same)?.let { return it }
        // The tree shifted between observing and acting (a driving map redraws lane views, a player
        // updates its status line). An unlabeled node cannot be re-identified; a labeled one that is
        // the only exact match in the same window is the same control.
        if (expected.text.isBlank() && expected.desc.isBlank()) return null.also { Log.w(TAG, "live: unlabeled node moved (${expected.path})") }
        val found = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root)); var seen = 0
        while (queue.isNotEmpty() && seen < 800 && found.size < 2) {
            val n = queue.removeFirst(); seen++
            if (same(n)) found += n
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let(queue::addLast)
        }
        if (found.size != 1) Log.w(TAG, "live: '${expected.text}${expected.desc}' found ${found.size} times after the tree shifted")
        return found.singleOrNull()
    }

    // ---------- actions ----------

    suspend fun click(snapshot: Snapshot, index: Int, long: Boolean): Boolean {
        val node = live(snapshot, index) ?: return false
        if (!node.isEnabled || !node.isVisibleToUser) {
            Log.w(TAG, "click: node enabled=${node.isEnabled} visible=${node.isVisibleToUser}"); node.recycleCompat(); return false
        }
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        // The element itself, then the nearest ancestor that handles clicks (Compose/RecyclerView rows).
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && hops < 6) {
            val handles = if (long) target.isLongClickable else target.isClickable
            if (handles && target.isEnabled && target.performAction(action)) return true
            target = target.parent; hops++
        }
        // Views that ignore ACTION_CLICK still respond to a real tap at their own bounds.
        val rect = Rect(); node.getBoundsInScreen(rect)
        return tap(rect.exactCenterX(), rect.exactCenterY(), if (long) 700 else 60)
    }

    /** Revalidate identity, enabled state and bounds before a gesture; never tap cached coordinates. */
    /** [trailing]: the icon drawn at the row's right end (a delete icon not exposed as its own node). */
    suspend fun tapObserved(snapshot: Snapshot, index: Int, trailing: Boolean = false): Boolean {
        val node = live(snapshot, index) ?: return false
        try {
            if (!node.isEnabled || !node.isVisibleToUser || node.isPassword) return false
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return false
            val x = if (trailing) bounds.right - bounds.height() * 0.4f else bounds.exactCenterX()
            return tap(x, bounds.exactCenterY(), 60)
        } finally { node.recycleCompat() }
    }

    fun setText(snapshot: Snapshot, index: Int, text: String, enter: Boolean): Boolean {
        val node = live(snapshot, index) ?: return false
        if (!node.isEditable) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
        if (ok && enter) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return ok
    }

    suspend fun scroll(snapshot: Snapshot, index: Int?, dir: ScrollDir): Boolean {
        // A real swipe inside the list: ACTION_SCROLL_FORWARD is often "accepted" by lists (Samsung
        // Settings) without moving them. Finger moves up to scroll down.
        val b = index?.let { snapshot.nodes.getOrNull(it)?.bounds }?.takeIf { !it.empty }
            ?: Bounds(0, 0, snapshot.width, snapshot.height)
        val path = ScrollGesture.path(b, snapshot.width, snapshot.height, dir) ?: return false
        return swipe(path.x1, path.y1, path.x2, path.y2)
    }

    /**
     * The list's own scroll action, for when a swipe moved nothing: some WebView lists (ClipStream's
     * playlist) ignore injected gestures but scroll on the accessibility action.
     */
    fun scrollByAction(snapshot: Snapshot, index: Int, dir: ScrollDir): Boolean {
        val node = live(snapshot, index) ?: return false
        try {
            val action = when (dir) {
                ScrollDir.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                ScrollDir.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                ScrollDir.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
                ScrollDir.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            }
            if (node.performAction(action.id)) return true
            val fallback = if (dir == ScrollDir.DOWN || dir == ScrollDir.RIGHT) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            return node.performAction(fallback)
        } finally { node.recycleCompat() }
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    private suspend fun tap(x: Float, y: Float, durationMs: Long): Boolean = withOverlayHidden {
        gesture(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, durationMs))
    }
    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean = withOverlayHidden {
        gesture(GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, 350))
    }
    private suspend fun gesture(stroke: GestureDescription.StrokeDescription): Boolean = suspendCancellableCoroutine { c ->
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { if (c.isActive) c.resume(true) }
            override fun onCancelled(g: GestureDescription?) { if (c.isActive) c.resume(false) }
        }, null)
        if (!ok && c.isActive) c.resume(false)
    }
    private suspend fun <T> withOverlayHidden(block: suspend () -> T): T {
        overlay?.visibility = View.GONE
        delay(30)
        try { return block() } finally { overlay?.visibility = View.VISIBLE }
    }

    // ---------- status overlay ----------

    fun showStatus(text: String) {
        if (instance !== this) return // A service rebind invalidates the previous overlay token.
        if (overlay == null) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply { setColor(Color.argb(235, 30, 30, 36)); cornerRadius = dp(22).toFloat() }
            }
            status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; maxLines = 2 }
            panel.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            panel.addView(Button(this).apply { setText("중지"); textSize = 12f; setOnClickListener { onCancel?.invoke() } })
            val params = WindowManager.LayoutParams(dp(320), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Keep the screen on while a command runs; a dozing phone freezes the model mid-task.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(36) }
            try {
                getSystemService(WindowManager::class.java).addView(panel, params)
            } catch (e: WindowManager.BadTokenException) {
                status = null
                Log.w(TAG, "overlay token unavailable after service rebind", e)
                return
            }
            overlay = panel
        }
        status?.text = text
    }
    /**
     * Shows [question] with one button per option and returns the chosen index, or null when the user
     * does not answer within [timeoutMs].
     */
    suspend fun ask(question: String, options: List<String>, timeoutMs: Long): Int? {
        showStatus(question)
        val panel = overlay ?: return null
        val buttons = options.mapIndexed { i, label -> Button(this).apply { setText(label); textSize = 13f; tag = i } }
        val choice = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Int> { c ->
                buttons.forEach { b ->
                    b.setOnClickListener { if (c.isActive) c.resume(b.tag as Int) }
                    panel.addView(b, panel.childCount - 1)
                }
            }
        }
        buttons.forEach { runCatching { panel.removeView(it) } }
        return choice
    }

    fun hideOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(it) } }
        overlay = null; status = null
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "AgentA11y"
        @Volatile var instance: AgentAccessibilityService? = null; private set
    }
}

@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.recycleCompat() { if (android.os.Build.VERSION.SDK_INT < 33) recycle() }
