package dev.localphone.agent

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.localphone.agent.runtime.*
import dev.localphone.agent.ui.VoiceButton
import dev.localphone.agent.ui.VoicePhase
import dev.localphone.core.InvocationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Default launcher/RegiStar target. No tool-specific logic or external command extras. */
class VoiceInvocationActivity : ComponentActivity() {
    private val graph get() = application as AgentApplication
    private val arrivedAt = SystemClock.elapsedRealtime()
    private lateinit var indicator: VoiceButton
    private lateinit var label: TextView
    internal var coordinator: VoiceSessionCoordinator? = null
        private set
    private var started = false
    private var onboarding = false
    private var onboardingChecked = false
    private lateinit var engine: AgentEngine
    private val setup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onboarding = false
        if (result.resultCode == RESULT_OK) window.decorView.post { beginWhenReady() }
        else closeEntry()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = AgentEngine(this, graph)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setDimAmount(0f)
        val panel = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.rgb(249, 249, 252)); cornerRadius = dp(26).toFloat() }
            elevation = dp(8).toFloat()
        }
        indicator = VoiceButton(this).apply { isClickable = false; isFocusable = false; setPhase(VoicePhase.PREPARING) }
        panel.addView(indicator, LinearLayout.LayoutParams(dp(40), dp(40)))
        label = TextView(this).apply { id = R.id.invocation_status; text = "준비 중…"; textSize = 15f; maxLines = 3; setTextColor(Color.rgb(53, 49, 71)) }
        panel.addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        panel.addView(ImageButton(this).apply {
            id = R.id.invocation_settings; contentDescription = "설정"; setImageResource(android.R.drawable.ic_menu_preferences)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                coordinator?.cancel()
                startActivity(Intent(this@VoiceInvocationActivity, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SETTINGS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                closeEntry()
            }
        }, LinearLayout.LayoutParams(dp(40), dp(48)))
        panel.addView(ImageButton(this).apply {
            id = R.id.invocation_cancel; contentDescription = "취소"; setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { coordinator?.cancel(); closeEntry() }
        }, LinearLayout.LayoutParams(dp(40), dp(48)))
        setContentView(panel)
        window.setLayout(dp(328), WindowManager.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { coordinator?.cancel(); closeEntry() }
        })
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        coordinator?.hostEvent(if (hasFocus) "FOCUS_GAINED" else "FOCUS_LOST")
        if (hasFocus) beginWhenReady()
    }
    override fun onResume() {
        super.onResume()
        coordinator?.hostEvent("RESUME")
        // A call delivered behind keyguard reports AUTH_REQUIRED without waking or dismissing it.
        if (!started && !onboarding && !ProfileScope(this).canAct()) startCoordinator()
    }
    private fun beginWhenReady() {
        if (started || onboarding || isFinishing) return
        if (!ProfileScope(this).canAct()) { startCoordinator(); return }
        if (graph.useAgentModel && graph.planner.selected == LocalModelId.FUNCTIONGEMMA && !graph.planner.gemmaTermsAccepted) {
            started = true
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            closeEntry(); return
        }
        if (!onboardingChecked) {
            onboardingChecked = true
            if (graph.settings.get("voice_onboarded") != "yes") {
                onboarding = true; setup.launch(Intent(this, VoiceOnboardingActivity::class.java)); return
            }
        }
        startCoordinator()
    }
    private fun startCoordinator() {
        if (started) return
        started = true
        coordinator = VoiceSessionCoordinator(graph, engine, lifecycleScope,
            graph.createCaptureFocus(), HapticVoiceFeedback(this, graph.settings), arrivedAt,
            object : VoiceSessionCoordinator.Observer {
                override fun onState(state: InvocationState, message: String) {
                    // Show the selected AI at this entry too, including capture and early failures.
                    // Model selection is the same installation-scoped setting as the center button.
                    val model = if (graph.useAgentModel) graph.embeddedModels.info(graph.planner.selected).name else "규칙 모드"
                    label.text = "$model · $message"
                    indicator.setPhase(when (state) {
                        InvocationState.STARTING -> VoicePhase.PREPARING
                        InvocationState.LISTENING -> VoicePhase.LISTENING
                        else -> VoicePhase.PROCESSING
                    })
                }
                override fun onLevel(level: Float) { indicator.setLevel(level) }
                override fun onFinished(state: InvocationState) {
                    indicator.setPhase(VoicePhase.IDLE)
                    // Success returns immediately. Failures get a short readable hint, without a blocking sleep.
                    if (state == InvocationState.SUCCESS || state == InvocationState.CANCELLED) closeEntry()
                    else lifecycleScope.launch { delay(1600); closeEntry() }
                }
            })
        if (coordinator?.start() != true) closeEntry()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); coordinator?.duplicate() }
    override fun onStop() {
        // RegiStar/launcher UI can briefly cover this entry while a final transcript is being
        // planned. Do not cancel an accepted command just because that activity is stopped.
        val accepted = coordinator?.state in listOf(InvocationState.TRANSCRIBING, InvocationState.PLANNING, InvocationState.POLICY_CHECK, InvocationState.EXECUTING)
        coordinator?.hostStopped(engine.externalExecution || accepted && engine.profile.canAct())
        if (!onboarding && !isChangingConfigurations && !engine.externalExecution && (!accepted || !engine.profile.canAct())) {
            coordinator?.cancel(); if (started && !isFinishing) closeEntry()
        }
        super.onStop()
    }
    override fun onDestroy() { coordinator?.hostEvent("DESTROY"); coordinator?.cancel(); super.onDestroy() }
    private fun closeEntry() { if (isTaskRoot) finishAndRemoveTask() else finish() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
