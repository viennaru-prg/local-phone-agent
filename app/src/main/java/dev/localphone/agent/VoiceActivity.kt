package dev.localphone.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.localphone.agent.access.AgentAccessibilityService
import dev.localphone.agent.speech.Listener

/**
 * The trigger. Opens as a small sheet over the current app, listens once, and hands the sentence to
 * [AgentService]. The model starts loading immediately so it is warm by the time speech ends.
 */
class VoiceActivity : Activity() {
    private var listener: Listener? = null
    private lateinit var title: TextView
    private lateinit var heard: TextView
    private var question: String? = null
    private var invocation = 0
    private var submitted = false
    private var origin = "VOICE_LAUNCHER"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    private fun handle(intent: Intent) {
        invocation++; submitted = false
        origin = if (intent.action == Intent.ACTION_ASSIST) "VOICE_ASSIST" else "VOICE_LAUNCHER"
        listener?.stop()
        question = intent.getStringExtra(EXTRA_QUESTION)
        // Debug builds: `adb shell am start -n dev.localphone.agent/.VoiceActivity --es goal "회사로 안내해줘"`
        // Korean text can be mangled on the way through adb on Windows, so scripts send it as base64.
        val typed = if (!BuildConfig.ADB_GOALS) null else intent.getStringExtra("goal_b64")
            ?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
            ?: intent.getStringExtra(AgentService.EXTRA_GOAL)
        // Debug builds: `--el dump_after 3000` logs the screen the agent sees once this sheet is gone.
        if (BuildConfig.ADB_GOALS && intent.hasExtra("dump_after")) {
            startForegroundService(Intent(this, AgentService::class.java).setAction(AgentService.ACTION_DUMP)
                .putExtra(AgentService.EXTRA_DELAY, intent.getLongExtra("dump_after", 2000)))
            finish(); return
        }
        android.util.Log.i("AgentVoice", "typed goal=${typed ?: "(none, listening)"}")
        // Debug builds: `--es alts_b64 <base64 of "후보1|후보2">` stands in for the recognizer's other hypotheses.
        val alternatives = if (!BuildConfig.ADB_GOALS) null else intent.getStringExtra("alts_b64")
            ?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
            ?.split('|')?.filter { it.isNotBlank() }
        val details = typed?.let { first -> alternatives?.let { alts ->
            org.json.JSONObject().put("hypotheses", org.json.JSONArray().apply {
                (listOf(first) + alts).forEach { put(org.json.JSONObject().put("text", it)) }
            }).toString()
        } }
        if (!typed.isNullOrBlank()) { origin = "ADB_TEXT"; submit(typed, details); return }
        val accessibilityEnabled = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == AgentAccessibilityService::class.java.name }
        // A cold launcher invocation can precede onServiceConnected. The service waits for binding;
        // microphone and text entry must not disagree merely because the process just restarted.
        if (AgentAccessibilityService.instance == null && !accessibilityEnabled) {
            title.text = "화면 조작 권한이 필요해요"
            heard.text = "설정 → 접근성 → 설치된 앱 → '음성 비서 화면 조작'을 켜 주세요."
            heard.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)); finish() }
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1); return
        }
        app.llm.preload()
        listen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { app.llm.preload(); listen() } else finish()
    }

    private fun listen() {
        title.text = question ?: "말씀하세요"
        heard.text = ""
        listener = Listener(this, onPartial = { heard.text = it }, onReady = {
            // A clear "speak now": words said before the microphone opened were lost ("안내해 줘" without its place).
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70).startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120) }
            title.text = question ?: "지금 말씀하세요"
        }).also { l ->
            l.start { result ->
                when (result) {
                    is Listener.Result.Text -> submit(result.text, result.details)
                    is Listener.Result.Error -> { title.text = result.message; heard.postDelayed({ finish() }, 1500) }
                }
            }
        }
    }

    private fun submit(text: String, details: String? = null) {
        if (submitted || isFinishing) return
        submitted = true
        val id = invocation
        heard.text = text
        if (question != null) AgentService.answer(this, text, origin, details) else AgentService.run(this, text, origin, details)
        heard.postDelayed({ if (id == invocation) finish() }, 300)
    }

    override fun onStop() { super.onStop(); listener?.stop(); listener = null; if (!isChangingConfigurations) finish() }

    private fun buildUi() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(28))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadii = FloatArray(8) { if (it < 4) dp(24).toFloat() else 0f } }
        }
        title = TextView(this).apply { textSize = 20f; setTextColor(Color.rgb(31, 111, 235)) }
        heard = TextView(this).apply { textSize = 17f; setTextColor(Color.rgb(40, 40, 40)); setPadding(0, dp(10), 0, 0); minLines = 2 }
        sheet.addView(title); sheet.addView(heard)
        val root = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            addView(sheet, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { finish() }
        }
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_QUESTION = "question" }
}
