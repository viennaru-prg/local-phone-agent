package dev.localphone.agent

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.*
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import dev.localphone.agent.runtime.*
import dev.localphone.core.*
import kotlinx.coroutines.*

/** Local, opt-in evaluation UI. It never executes the recognized command. */
class SpeechEvaluationActivity : ComponentActivity() {
    private val graph get() = application as AgentApplication
    private val engine by lazy { AgentEngine(this, graph) }
    private val evaluator by lazy { SpeechEvaluator(graph) }
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var reference: EditText
    private lateinit var category: TextView
    private lateinit var consent: CheckBox
    private lateinit var saveAudio: CheckBox
    private lateinit var environment: Spinner
    private lateinit var focusMode: Spinner
    private lateinit var source: Spinner
    private var cases = emptyList<SpeechBenchmarkCase>()
    private var caseIndex = 0
    private var job: Job? = null
    private var recorder: SpeechEvaluationCapture? = null
    private var input: SpeechInput? = null
    private var focus: CaptureFocus? = null
    private var generation = 0
    private var busy = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status.text = if (granted) "마이크를 허용했습니다. 테스트 버튼을 다시 눌러 주세요." else "마이크 권한을 허용하면 평가할 수 있습니다."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 60, 32, 40) }
        fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setPadding(0, 12, 0, 12) }.also(body::addView)
        fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; isAllCaps = false; setOnClickListener { action() } }.also(body::addView)
        button("‹ 설정으로") { finish() }
        text("한국어 음성 인식 평가", 23f)
        text("평가에서는 인식한 명령을 실행하지 않습니다. 녹음과 결과를 외부 서버로 보내지 않습니다.")
        status = text("기기 지원 확인 중…")
        button("기기 지원 다시 확인") { if (!busy) checkSupport() }
        button("저장 없이 온디바이스 인식 테스트") { if (!busy && microphoneAllowed()) liveTest() }
        text("평가 환경", 18f)
        environment = Spinner(this).also { it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("조용한 방", "음악 재생 중", "차량 정차", "차량 주행")); body.addView(it) }
        focusMode = Spinner(this).also { it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("음악 duck", "음악 임시 pause (오디오 포커스)")); body.addView(it) }
        source = Spinner(this).also { it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("VOICE_RECOGNITION", "MIC", "UNPROCESSED (지원 기기)")); body.addView(it) }
        text("같은 PCM으로 기존 포함 모델, Native bias 0/12/24, N-best 보정을 비교합니다. 반복 녹음의 차이가 비교에 섞이지 않게 합니다.")
        text("비행기 모드에서 Wi-Fi·모바일 데이터도 꺼진 상태로 테스트하면 오프라인 성공 여부를 확인할 수 있습니다. 차량에서는 안전하게 정차한 뒤 조작해 주세요.")
        consent = CheckBox(this).apply { text = "평가 문장·인식 결과를 이 휴대폰 내부에 기록하는 데 동의"; isChecked = false }.also(body::addView)
        saveAudio = CheckBox(this).apply { text = "평가 음성 파일도 이 휴대폰에 저장 (선택)"; isChecked = false }.also(body::addView)
        category = text("", 18f)
        reference = EditText(this).apply { textSize = 20f; hint = "실제로 말할 문장" }.also(body::addView)
        button("이 문장 녹음·비교") {
            if (!busy && !consent.isChecked) status.text = "평가 기록에 동의한 뒤 시작해 주세요. 일반 음성 호출은 녹음을 저장하지 않습니다."
            else if (!busy && microphoneAllowed()) evaluate()
        }
        button("녹음 종료 / 인식 취소") { if (recorder != null) recorder?.stop() else cancel() }
        button("다음 문장") { if (!busy) { caseIndex = (caseIndex + 1).coerceAtMost(cases.lastIndex); showCase() } }
        button("이전 문장") { if (!busy) { caseIndex = (caseIndex - 1).coerceAtLeast(0); showCase() } }
        button("이 포커스 전략을 일반 음성 호출에 적용") {
            if (!busy) { graph.settings.put("stt_focus_mode", if (focusMode.selectedItemPosition == 1) "pause" else "duck"); status.text = "선택한 포커스 전략을 적용했습니다." }
        }
        button("평가 기록·저장 음성 모두 삭제") { if (!busy) {
            android.app.AlertDialog.Builder(this).setTitle("평가 기록을 지울까요?").setMessage("이 휴대폰에 저장된 음성 평가 기록과 평가 음성만 삭제합니다.")
                .setPositiveButton("삭제") { _, _ -> evaluator.clear(); summary.text = evaluator.summary(); caseIndex = 0; showCase() }.setNegativeButton("취소", null).show()
        } }
        summary = text("", 14f)
        setContentView(ScrollView(this).apply { addView(body) })
        cases = Gson().fromJson(assets.open("stt-evaluation-ko.json").bufferedReader().use { it.readText() }, Array<SpeechBenchmarkCase>::class.java).toList()
        val completed = evaluator.records().map { it.case.id }.toSet()
        caseIndex = cases.indexOfFirst { it.id !in completed }.coerceAtLeast(0)
        showCase(); summary.text = evaluator.summary(); checkSupport()
    }
    private fun microphoneAllowed(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        permissions.launch(Manifest.permission.RECORD_AUDIO); return false
    }
    private fun showCase() {
        if (cases.isEmpty()) return
        category.text = "${caseIndex + 1}/${cases.size} · ${cases[caseIndex].category}"
        reference.setText(cases[caseIndex].text)
    }
    private fun checkSupport() {
        lifecycleScope.launch {
            val support = queryNativeSpeechSupport(this@SpeechEvaluationActivity)
            status.text = "Android native on-device: ${support.available}\nko-KR 설치: ${support.koreanReady}\n상태: ${support.status} / 오류 ${support.errorCode ?: "없음"}\n" +
                "Installed=${support.installedLanguages.joinToString()}\nOnline=${support.onlineLanguages.joinToString()}\n" +
                "NS=${support.noiseSuppressorAvailable}; AEC=${support.echoCancelerAvailable}; AGC=${support.gainControlAvailable}; UNPROCESSED=${support.unprocessedSourceAvailable}\n" +
                "이 DSP 정보는 지원 여부입니다. Native 내부에서 활성화됐다는 판정이 아닙니다."
        }
    }
    private fun liveTest() {
        if (Build.VERSION.SDK_INT < 31) { status.text = "Android 온디바이스 인식 API를 지원하지 않습니다."; return }
        val token = ++generation; busy = true
        job = lifecycleScope.launch {
            val context = engine.speechContext()
            val startedNetwork = evaluationNetwork(this@SpeechEvaluationActivity)
            focus = CaptureAudioFocus(this@SpeechEvaluationActivity, if (focusMode.selectedItemPosition == 1) CaptureFocusMode.TEMPORARY_PAUSE else CaptureFocusMode.DUCK)
            if (focus?.acquire { cancel() } != true) { busy = false; status.text = "오디오 포커스를 얻지 못했습니다."; return@launch }
            delay(150)
            input = OnDeviceSpeechInput(this@SpeechEvaluationActivity, context.biasStrings()).also { speech -> speech.start(object : SpeechInput.Listener {
                private fun current() = token == generation
                override fun onListening() { if (current()) { status.text = "마이크 준비 완료. 자연스럽게 말해 주세요."; HapticVoiceFeedback(this@SpeechEvaluationActivity, graph.settings).ready() } }
                override fun onLevel(level: Float) = Unit
                override fun onPartial(text: String) { if (current()) status.text = "듣는 중… $text" }
                override fun onFinal(text: String) = Unit
                override fun onSpeechEnded() { focus?.release() }
                override fun onRecognition(result: SpeechRecognitionResult) {
                    if (!current()) return
                    busy = false; focus?.release()
                    val resolved = graph.speechDiagnostics.resolve(result, context)
                    status.text = "온디바이스 인식 완료 / offline=${startedNetwork.offline && evaluationNetwork(this@SpeechEvaluationActivity).offline}\n" +
                        result.hypotheses.joinToString("\n") { "#${it.rank + 1} ${it.text} / ${it.acousticConfidence ?: "신뢰도 미제공"}" } +
                        "\n선택: ${resolved.selectedText}\n${resolved.evidence.joinToString()}\n준비 ${result.readyLatencyMs}ms / 전체 ${result.totalLatencyMs}ms\n" +
                        "명령 실행·녹음 파일 저장 없음"
                }
                override fun onError(message: String) { if (current()) { busy = false; focus?.release(); status.text = message } }
            }) }
        }
    }
    private fun evaluate() {
        if (cases.isEmpty()) return
        val case = cases[caseIndex]; val expected = reference.text.toString().trim()
        if (expected.isBlank()) { status.text = "실제로 말할 문장을 입력해 주세요."; return }
        val token = ++generation; busy = true
        val save = saveAudio.isChecked; val environmentName = environment.selectedItem.toString()
        val mode = if (focusMode.selectedItemPosition == 1) CaptureFocusMode.TEMPORARY_PAUSE else CaptureFocusMode.DUCK
        val audioSource = when (source.selectedItemPosition) { 1 -> MediaRecorder.AudioSource.MIC; 2 -> MediaRecorder.AudioSource.UNPROCESSED; else -> MediaRecorder.AudioSource.VOICE_RECOGNITION }
        job = lifecycleScope.launch {
            try {
                val context = engine.speechContext()
                if (audioSource == MediaRecorder.AudioSource.UNPROCESSED && !queryNativeSpeechSupport(this@SpeechEvaluationActivity).unprocessedSourceAvailable)
                    error("이 기기는 UNPROCESSED 지원을 보고하지 않았습니다. 다른 소스를 선택해 주세요.")
                focus = CaptureAudioFocus(this@SpeechEvaluationActivity, mode)
                check(focus?.acquire { cancel() } == true) { "오디오 포커스를 얻지 못했습니다." }
                status.text = "마이크 준비 중…"; delay(150)
                val capture = SpeechEvaluationCapture(this@SpeechEvaluationActivity, audioSource); recorder = capture
                val audio = capture.capture({
                    status.text = "준비 완료. ‘$expected’를 자연스럽게 말해 주세요."; HapticVoiceFeedback(this@SpeechEvaluationActivity, graph.settings).ready()
                }, { /* level stays local; no transcript or command exists yet */ })
                recorder = null; focus?.release()
                val record = evaluator.evaluate(case, expected, audio, context, environmentName, mode.name, save) { stage ->
                    status.text = "같은 음성 비교 중… $stage"
                }
                if (token != generation) return@launch
                status.text = "비교 완료 (${record.audioDurationMs} ms 음성)\n" + record.variants.joinToString("\n") {
                    "${it.name}: ${it.selectedText.ifBlank { "인식 실패" }} / entity=${it.measure.entity ?: "미측정"} / ${it.wallMs} ms"
                }
                summary.text = evaluator.summary()
                caseIndex = (caseIndex + 1).coerceAtMost(cases.lastIndex); showCase()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (token == generation) status.text = error.message ?: "평가를 완료하지 못했습니다." }
            finally { focus?.release(); recorder = null; if (token == generation) busy = false }
        }
    }
    private fun cancel() {
        generation++; recorder?.stop(); recorder = null; input?.cancel(); input = null
        job?.cancel(); focus?.release(); focus = null; busy = false
        if (::status.isInitialized) status.text = "평가를 취소했습니다. 명령은 실행하지 않았습니다."
    }
    override fun onStop() { cancel(); super.onStop() }
}
