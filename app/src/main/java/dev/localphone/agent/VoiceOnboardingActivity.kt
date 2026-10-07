package dev.localphone.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.localphone.agent.runtime.ProfileScope

class VoiceOnboardingActivity : ComponentActivity() {
    private val graph get() = application as AgentApplication
    private lateinit var status: TextView
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) complete() else status.text = "음성 호출에는 마이크 권한이 필요합니다."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(48), dp(28), dp(40)); setBackgroundColor(Color.rgb(249, 249, 252))
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.rgb(53, 49, 71)); setPadding(0, dp(8), 0, dp(12))
        }.also(panel::addView)
        text("말하면 폰에서 실행해요", 26f)
        text("마이크를 한 번 허용하면 앱 실행 직후 바로 들을 수 있어요. 짧게 진동하면 말해주세요.", 17f)
        text("한국어 음성은 폰 안에서 처리합니다. 녹음 파일을 저장하거나 AI 서버에 보내지 않습니다.", 16f)
        text(ProfileScope(this).description(), 14f)
        text("장소나 앱 연결을 미리 등록할 필요는 없습니다. 공식 연동이 없으면 설정의 화면 작업 연결을 통해 앱 화면에서 계속 수행합니다. 복잡한 목표 해석에는 로컬 모델을 추가할 수 있어요.", 14f)
        if (!BuildConfig.MEDIA_SESSION_CONTROL_AVAILABLE)
            text("이 설치판은 음악 직접 제어를 지원하지 않습니다. 시계·알람·타이머·설정·내비를 사용할 수 있어요.", 14f)
        status = text("", 14f)
        panel.addView(Button(this).apply {
            id = R.id.voice_onboarding_start; text = "마이크 허용하고 시작"
            setOnClickListener {
                if (!ProfileScope(this@VoiceOnboardingActivity).canAct()) { status.text = "잠금 해제 후 다시 실행해 주세요."; return@setOnClickListener }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) complete()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        })
        panel.addView(Button(this).apply { text = "먼저 설정하기"; setOnClickListener {
            startActivity(Intent(this@VoiceOnboardingActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } })
        setContentView(ScrollView(this).apply { addView(panel) })
    }
    private fun complete() { graph.settings.put("voice_onboarded", "yes"); setResult(RESULT_OK); finish() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
