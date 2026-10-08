package dev.localphone.agent

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.localphone.agent.updates.*
import dev.localphone.agent.runtime.*
import dev.localphone.agent.ui.VoiceButton
import dev.localphone.agent.ui.VoicePhase
import dev.localphone.core.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_SETTINGS = "open_settings" }
    private val graph get() = application as AgentApplication
    private val engine by lazy { AgentEngine(this, graph) }
    private val navigation get() = engine.navigation
    private val media get() = engine.media
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var input: EditText
    private var activeJobs = 0
    private val busy get() = activeJobs > 0
    private var commandText = ""
    private var pendingLocationName = ""
    private val useModel get() = graph.useAgentModel
    private var showingSettings = false
    private var statusMessage = ""
    private var transcript = ""
    private var voicePhase = VoicePhase.IDLE
    private var voiceGeneration = 0
    private var speech: SpeechInput? = null
    private var voicePreparation: kotlinx.coroutines.Job? = null
    private var voiceWarming: kotlinx.coroutines.Job? = null
    private var voiceStartedAt = 0L
    private var voiceMetrics: SpeechMetrics? = null
    private val voiceTimes = linkedMapOf<String, Long>()
    private var captureFocus: CaptureFocus? = null
    private var permissionPending = false
    private var voiceButton: VoiceButton? = null
    private var voiceHeading: TextView? = null
    private var voiceTranscript: TextView? = null
    private val updater by viewModels<AppUpdateViewModel>()
    private var updateButton: Button? = null
    private var updateStatus: TextView? = null
    private var updateNotes: TextView? = null
    private var preparingInstall = false
    private var modelGroup: RadioGroup? = null
    private val modelChoices = mutableMapOf<LocalModelId, RadioButton>()
    private var renderingModels = false
    private val updateInstallPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ready = updater.state.value as? UpdateState.Ready
        if (ready != null && updater.client.canInstall()) installUpdate(ready)
        else tell("업데이트 APK를 받았습니다. 설치를 허용한 뒤 '업데이트 설치'를 눌러 주세요.")
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (permissionPending) {
            permissionPending = false
            if (allowed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) && !showingSettings) startVoice()
            else { setVoicePhase(VoicePhase.IDLE); tell("마이크를 허용하면 음성으로 요청할 수 있어요.") }
        }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) captureLocation(pendingLocationName)
        else tell("정확한 위치 권한이 필요합니다. 위치는 저장 확인을 누를 때만 기록됩니다.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        commandText = savedInstanceState?.getString("command").orEmpty()
        showingSettings = savedInstanceState?.getBoolean("showingSettings") ?: intent.getBooleanExtra(EXTRA_SETTINGS, false)
        statusMessage = savedInstanceState?.getString("status").orEmpty()
        transcript = savedInstanceState?.getString("transcript").orEmpty()
        pendingLocationName = savedInstanceState?.getString("locationName").orEmpty()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingSettings) { commandText = input.text.toString(); showingSettings = false; render() }
                else if (voicePhase in listOf(VoicePhase.PREPARING, VoicePhase.LISTENING)) cancelVoice()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        render()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { updater.state.collect { renderUpdateState(it) } }
                launch { graph.planner.status.collect { renderModelState(it) } }
            }
        }
        receiveShare(intent)
        if (useModel && graph.planner.selected == LocalModelId.FUNCTIONGEMMA && !graph.planner.gemmaTermsAccepted) modelTerms()
        if (intent.getBooleanExtra("open_speech_evaluation", false)) {
            intent.removeExtra("open_speech_evaluation")
            startActivity(Intent(this, SpeechEvaluationActivity::class.java))
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("command", if (showingSettings && ::input.isInitialized) input.text.toString() else commandText)
        outState.putString("locationName", pendingLocationName)
        outState.putBoolean("showingSettings", showingSettings)
        outState.putString("status", statusMessage)
        outState.putString("transcript", transcript)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveShare(intent) }
    override fun onStop() {
        if (voicePhase in listOf(VoicePhase.PREPARING, VoicePhase.LISTENING)) cancelVoice(silent = true)
        super.onStop()
    }
    override fun onDestroy() { voiceGeneration++; speech?.close(); speech = null; super.onDestroy() }

    private fun render() {
        voiceButton = null; voiceHeading = null; voiceTranscript = null
        updateButton = null; updateStatus = null; updateNotes = null
        if (showingSettings) renderSettings() else renderVoiceHome()
    }

    private fun renderVoiceHome() {
        val root = column().apply { setBackgroundColor(Color.rgb(249, 249, 252)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(26), dp(8), dp(16), 0) }
        header.addView(label("AI", 20, bold = true).apply { setTextColor(Color.rgb(72, 69, 88)) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ImageButton(this).apply {
            id = R.id.agent_settings
            contentDescription = "설정"
            setImageResource(android.R.drawable.ic_menu_preferences)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(118, 115, 134))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { if (!busy) { cancelVoice(silent = true); showingSettings = true; render() } }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(64)))
        val center = FrameLayout(this)
        val cluster = column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(32), 0, dp(32), 0) }
        voiceButton = VoiceButton(this).apply {
            id = R.id.voice_button
            setOnClickListener { toggleVoice() }
        }.also { cluster.addView(it, LinearLayout.LayoutParams(dp(240), dp(240))) }
        voiceHeading = label("", 22, bold = true).apply { id = R.id.voice_heading; gravity = Gravity.CENTER; setTextColor(Color.rgb(53, 49, 71)) }
            .also { cluster.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }) }
        voiceTranscript = label(transcript, 17).apply { id = R.id.voice_transcript; gravity = Gravity.CENTER; maxLines = 3 }
            .also { cluster.addView(it, LinearLayout.LayoutParams(-1, dp(66)).apply { topMargin = dp(12) }) }
        status = label(statusMessage, 14).apply {
            id = R.id.command_status; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setTextColor(Color.rgb(129, 125, 145)); maxLines = 3
        }
        cluster.addView(status, LinearLayout.LayoutParams(-1, dp(68)))
        center.addView(cluster, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(center, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
        setContentView(root)
        updateVoiceHome()
    }

    private fun renderSettings() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(244, 247, 245)) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(28))
        }
        scroll.addView(body)
        @Suppress("DEPRECATION")
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets
        }
        setContentView(scroll)
        body.addView(smallButton("‹  음성 화면으로") { commandText = input.text.toString(); showingSettings = false; render() }.apply { id = R.id.agent_back })
        addText("설정", 24, bold = true)
        addText("장소 기억과 앱 연결은 선택 사항입니다. 연결이 없으면 앱 화면에서 계속 수행합니다.", 14)
        space()
        addText("앱 업데이트", 18, bold = true)
        addText("현재 버전 ${BuildConfig.VERSION_NAME}", 14)
        updateButton = smallButton("업데이트 확인") {
            if (!busy) {
                val ready = updater.state.value as? UpdateState.Ready
                if (ready != null) installUpdate(ready) else updater.checkAndDownload()
            }
        }.apply { id = R.id.update_button }.also(body::addView)
        updateStatus = addText("GitHub에서 새 버전을 확인합니다.", 14).apply { id = R.id.update_status }
        updateNotes = addText("", 13).apply { id = R.id.update_notes }
        addText("새 버전이 있으면 변경 내용을 표시하며 APK를 받습니다. 최종 설치는 Android 화면에서 승인합니다.", 13)
        renderUpdateState(updater.state.value)
        space()
        addText("텍스트로 테스트", 18, bold = true)
        input = edit("예: 5분 타이머 시작해줘", commandText)
        input.id = R.id.command_input
        body.addView(input)
        button("명령 실행") { commandText = input.text.toString(); handleCommand(commandText) }
        status = addText(statusMessage.ifBlank { "예: 시계 앱 열어줘 / 오전 7시 알람 맞춰줘 / 와이파이 설정 열어줘" }, 14)
        status.id = R.id.command_status
        space()
        addText("내 장소", 21, bold = true)
        rowButtons("집 설정" to { registration("집") }, "회사 설정" to { registration("회사") })
        button("즐겨찾기 추가") { registration("") }
        button("현재 위치 기억하기") { askLocationName() }
        val placeContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(placeContainer)
        launchSafe {
            val places = withContext(Dispatchers.IO) { graph.places.all() }
            if (places.isEmpty()) placeContainer.addView(label("미리 등록하지 않아도 지도 앱의 저장 장소와 검색 화면에서 찾습니다.", 14))
            for (place in places) {
                val card = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = rounded(Color.WHITE)
                }
                card.addView(label(place.canonicalName, 18, bold = true))
                card.addView(label(place.aliases.joinToString(" · "), 13))
                if (place.address.isNotBlank()) card.addView(label(place.address, 13))
                val actions = LinearLayout(this@MainActivity)
                actions.addView(smallButton("별칭 추가") { addAlias(place) })
                actions.addView(smallButton("수정") { saveDialog(place.toCandidate(), place.canonicalName, place) })
                actions.addView(smallButton("삭제") { deletePlace(place) })
                card.addView(actions)
                placeContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
        }
        space()
        addText("연결 설정", 21, bold = true)
        addText(engine.profile.description(), 13)
        addText("현재 설치: ${BuildConfig.VERSION_NAME} / ${engine.profile.user}", 13)
        button("자동 음성 호출 테스트") {
            if (!busy) {
                cancelVoice(silent = true)
                startActivity(Intent(this, VoiceInvocationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        button("마지막 음성 호출 기록") { startActivity(Intent(this, InvocationDebugActivity::class.java)) }
        button("음성 호출 설정") { invocationSettings() }
        button("화면 작업 연결") { configureAccessibility() }
        body.addView(Switch(this).apply {
            text = "안내 시작이 확인된 장소를 다음 실행에 기억"
            isChecked = graph.settings.get("cache_ui_places") != "no"
            setOnCheckedChangeListener { _, checked -> graph.settings.put("cache_ui_places", if (checked) "yes" else "no") }
        })
        button("네이버 장소 검색 설정 (빠른 경로)") { searchSettings() }
        button(if (media.availableInThisBuild) "음악 제어 연결 (빠른 경로)" else "음악 직접 제어 안내") { configureMedia() }
        addText("내장 AI 모델", 21, bold = true)
        modelChoices.clear()
        modelGroup = RadioGroup(this).also { group ->
            LocalModelId.entries.forEach { id ->
                val choice = RadioButton(this).apply { this.id = View.generateViewId() }
                modelChoices[id] = choice; group.addView(choice)
            }
            body.addView(group)
            group.setOnCheckedChangeListener { _, checked ->
                if (!renderingModels && !busy) modelChoices.entries.firstOrNull { it.value.id == checked }?.key?.let(::chooseModel)
            }
        }
        renderModelState(graph.planner.status.value)
        button("선택 모델 준비 · 다시 시도") { chooseModel(graph.planner.selected) }
        button("최근 모델 추론 확인") {
            val planner = graph.planner
            AlertDialog.Builder(this).setTitle("로컬 모델 실행 기록")
                .setMessage("선택: ${planner.selected.key}\n현재 로드: ${planner.loadedId ?: "없음"}\n" +
                    "준비: ${planner.lastLoad ?: "실행 전"}\n추론: ${planner.lastInferenceMs ?: "없음"} ms\n" +
                    "추출·엔진·검사: ${planner.lastLoadTimings ?: "실행 전"}\n" +
                    "오류: ${planner.lastError.ifBlank { "없음" }}\n\n최근 실제 모델 출력:\n" +
                    (planner.lastResponse?.raw ?: "명령 추론 전") + "\n\n최근 준비 검사 출력:\n" +
                    (planner.lastProbe?.raw ?: "준비 검사 전"))
                .setPositiveButton("닫기", null).show()
        }
        button("모델 사용 약관 · 라이선스") { modelTerms() }
        addText("두 모델 가중치가 APK에 포함되어 있습니다. 한 번에 하나만 로드하며, 작업이 끝난 뒤 30초 동안 사용하지 않으면 메모리를 해제합니다. 모델 전환 중에는 현재 선택을 유지하고 실제 추론 검사가 끝나야 변경됩니다.", 13)
        body.addView(Switch(this).apply {
            text = "Android 온디바이스 한국어 인식 사용 (기본)"
            isChecked = graph.settings.get("speech_engine") != "bundled"
            setOnCheckedChangeListener { _, checked -> graph.settings.put("speech_engine", if (checked) "native" else "bundled") }
        })
        button("음성 인식 상태·평가") { startActivity(Intent(this, SpeechEvaluationActivity::class.java)) }
        addText("선택한 AI가 명령을 해석하고 공식 연결·화면 작업으로 이어집니다. 모델 실패 시 규칙 기반 경로를 시도하며, 그 구분과 오류는 마지막 호출 기록에서 확인할 수 있습니다.", 13)
        addText("기본값은 Android 온디바이스 한국어 인식입니다. 기존 포함 모델은 직접 선택할 수 있습니다. 음성을 클라우드로 보내지 않으며 일반 음성 호출은 녹음 파일을 저장하지 않습니다.", 13)
    }

    private fun toggleVoice() {
        if (useModel && graph.planner.selected == LocalModelId.FUNCTIONGEMMA && !graph.planner.gemmaTermsAccepted) {
            modelTerms { toggleVoice() }; return
        }
        if (voicePhase in listOf(VoicePhase.PREPARING, VoicePhase.LISTENING)) { cancelVoice(); return }
        if (busy || permissionPending) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else startVoice()
    }
    private fun renderUpdateState(state: UpdateState) {
        updateButton?.isEnabled = state !is UpdateState.Checking && state !is UpdateState.Downloading && !preparingInstall
        updateButton?.text = if (state is UpdateState.Ready) "업데이트 설치" else "업데이트 확인"
        updateStatus?.text = when (state) {
            UpdateState.Idle -> "GitHub에서 새 버전을 확인합니다."
            UpdateState.Checking -> "새 버전 확인 중…"
            is UpdateState.Downloading -> "${state.update.versionName} 다운로드 ${state.percent}%"
            is UpdateState.Ready -> "다운로드와 APK 검사가 완료되었습니다. Android 설치 승인이 필요합니다."
            UpdateState.Current -> "사용 가능한 새 업데이트가 없습니다."
            is UpdateState.Failed -> state.message
        }
        updateNotes?.text = when (state) {
            is UpdateState.Downloading -> state.update.notes
            is UpdateState.Ready -> state.update.notes
            else -> ""
        }
        if (showingSettings && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && state is UpdateState.Ready && state.offerInstallation)
            installUpdate(state)
    }
    private fun installUpdate(ready: UpdateState.Ready) {
        if (preparingInstall || !showingSettings) return
        preparingInstall = true
        updater.installationOffered()
        updateButton?.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { updater.client.verifyArchive(ready.file, ready.update) }
                if (!showingSettings || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                if (!engine.profile.canAct()) { tell("잠금 해제 후 업데이트를 설치해 주세요."); return@launch }
                if (updater.client.canInstall()) startActivity(updater.client.installIntent(ready.file))
                else {
                    tell("다운로드 완료. Android 설정에서 이 앱의 업데이트 설치를 허용해 주세요.")
                    updateInstallPermission.launch(updater.client.permissionIntent())
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { updater.installationFailed(failure.message ?: "업데이트 설치 화면을 열지 못했습니다.") }
            finally { preparingInstall = false; renderUpdateState(updater.state.value) }
        }
    }
    private fun invocationSettings() {
        val timing = graph.speechTiming()
        val fields = column()
        fields.addView(label("앱 실행 시 자동으로 듣고 완료하면 닫힙니다.", 14))
        val haptics = Switch(this).apply { text = "준비·완료 진동"; isChecked = graph.settings.get("voice_haptics") != "no" }
        fields.addView(haptics)
        fields.addView(label("말하기 대기: 3–8초", 13))
        val start = edit("초", (timing.startTimeoutMs / 1000f).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        fields.addView(start)
        fields.addView(label("말 끝의 무음: 0.7–1.5초", 13))
        val silence = edit("초", (timing.trailingSilenceMs / 1000f).toString()).apply { inputType = start.inputType }
        fields.addView(silence)
        fields.addView(label("한 번에 최대: 15–20초", 13))
        val maximum = edit("초", (timing.maxUtteranceMs / 1000f).toString()).apply { inputType = start.inputType }
        fields.addView(maximum)
        val dialog = AlertDialog.Builder(this).setTitle("음성 호출").setView(padded(fields))
            .setPositiveButton("저장", null).setNegativeButton("닫기", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val updated = runCatching { SpeechTiming((start.text.toString().toFloat() * 1000).toInt(),
                (silence.text.toString().toFloat() * 1000).toInt(), (maximum.text.toString().toFloat() * 1000).toInt()) }.getOrNull()
            if (updated == null) { tell("각 시간의 범위를 확인해 주세요."); return@setOnClickListener }
            graph.settings.put("voice_haptics", if (haptics.isChecked) "yes" else "no")
            graph.settings.put("speech_start_ms", updated.startTimeoutMs.toString())
            graph.settings.put("speech_silence_ms", updated.trailingSilenceMs.toString())
            graph.settings.put("speech_max_ms", updated.maxUtteranceMs.toString())
            dialog.dismiss()
        } }; dialog.show()
    }
    private fun startVoice() {
        val generation = ++voiceGeneration
        voiceStartedAt = SystemClock.elapsedRealtime(); voiceMetrics = null
        voiceTimes.clear(); voiceTimes["T0"] = 0; voiceTimes["T1"] = 0
        speech?.close()
        transcript = ""; statusMessage = ""
        setVoicePhase(VoicePhase.PREPARING)
        voicePreparation = lifecycleScope.launch {
            val context = try { engine.speechContext() } catch (_: Exception) {
                if (generation == voiceGeneration) { setVoicePhase(VoicePhase.IDLE); tell("음성 입력을 준비하지 못했습니다.") }; return@launch
            }
            if (generation != voiceGeneration || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            val focus = graph.createCaptureFocus(); captureFocus = focus
            if (!focus.acquire { cancelVoice(silent = true) }) { setVoicePhase(VoicePhase.IDLE); tell("음성 입력을 위한 오디오 사용 권한을 얻지 못했습니다."); return@launch }
            if (graph.speechFactoryOverride == null) kotlinx.coroutines.delay(150)
            if (generation != voiceGeneration) { focus.release(); return@launch }
        speech = graph.createSpeechInput(context).also { source -> source.start(object : SpeechInput.Listener {
            private fun current() = generation == voiceGeneration && voicePhase in listOf(VoicePhase.PREPARING, VoicePhase.LISTENING)
            override fun onListening() { if (current()) { voiceTimes["T2"] = SystemClock.elapsedRealtime() - voiceStartedAt; setVoicePhase(VoicePhase.LISTENING); HapticVoiceFeedback(this@MainActivity, graph.settings).ready() } }
            override fun onLevel(level: Float) { if (current()) voiceButton?.setLevel(level) }
            override fun onPartial(text: String) { if (current()) { transcript = text; updateVoiceHome() } }
            override fun onFinal(text: String) {
                onRecognition(SpeechRecognitionResult(listOf(SpeechHypothesis(text, null, 0)), engine = "legacy-input-callback", onDevice = true))
            }
            override fun onRecognition(result: SpeechRecognitionResult) {
                if (!current()) return
                if (!result.finalResult) return
                if (voicePhase != VoicePhase.LISTENING) { onError("마이크 준비를 확인하지 못했습니다."); return }
                // Retire this session before dispatch: duplicate or late results cannot run another command.
                voiceTimes["T5"] = SystemClock.elapsedRealtime() - voiceStartedAt
                voiceGeneration++; source.cancel(); focus.release()
                val resolved = graph.speechDiagnostics.resolve(result, context)
                val command = resolved.selectedText.trim().trimEnd('.', '。', '!')
                if (command.isBlank()) { setVoicePhase(VoicePhase.IDLE); tell("잘 듣지 못했어요. 다시 눌러 말해주세요."); return }
                transcript = command; commandText = command
                setVoicePhase(VoicePhase.IDLE)
                if (resolved.requiresClarification) {
                    val choices = resolved.candidates.take(3).distinctBy { it.semanticKey }
                    tell(resolved.clarification)
                    if (choices.isNotEmpty()) AlertDialog.Builder(this@MainActivity).setTitle("어느 명령인가요?")
                        .setItems(choices.map { it.text }.toTypedArray()) { _, index ->
                            val chosen = resolved.copy(selectedText = choices[index].text, requiresClarification = false,
                                evidence = resolved.evidence + "HUMAN_SCREEN_TRANSCRIPT_CHOICE")
                            transcript = chosen.selectedText; handleCommand(chosen.selectedText, chosen)
                        }.setNegativeButton("취소", null).show()
                } else handleCommand(command, resolved)
            }
            override fun onSpeechStarted() { if (current()) voiceTimes["T3"] = SystemClock.elapsedRealtime() - voiceStartedAt }
            override fun onSpeechEnded() { if (current()) { voiceTimes["T4"] = SystemClock.elapsedRealtime() - voiceStartedAt; focus.release() } }
            override fun onMetrics(metrics: SpeechMetrics) { if (current()) voiceMetrics = metrics }
            override fun onError(message: String) {
                if (!current()) return
                voiceGeneration++; source.cancel(); focus.release(); transcript = ""
                setVoicePhase(VoicePhase.IDLE); tell(message)
            }
        }) }
        if (useModel && generation == voiceGeneration) voiceWarming = lifecycleScope.launch { graph.planner.prepare() }
        }
    }
    private fun cancelVoice(silent: Boolean = false) {
        voiceGeneration++; permissionPending = false
        voicePreparation?.cancel(); voicePreparation = null
        voiceWarming?.cancel(); voiceWarming = null
        if (useModel && !graph.invocationArbiter.isActive) { graph.planner.cancel(); graph.planner.releaseAfterIdle() }
        captureFocus?.release(); captureFocus = null
        speech?.cancel(); speech = null
        if (voicePhase in listOf(VoicePhase.PREPARING, VoicePhase.LISTENING)) {
            transcript = ""; setVoicePhase(VoicePhase.IDLE)
            if (!silent) tell("취소했어요. 다시 눌러 말해주세요.")
        }
    }
    private fun setVoicePhase(phase: VoicePhase) { voicePhase = phase; updateVoiceHome() }
    private fun updateVoiceHome() {
        voiceButton?.setPhase(voicePhase)
        voiceHeading?.text = when (voicePhase) {
            VoicePhase.IDLE -> "눌러서 말해주세요"
            VoicePhase.PREPARING -> "준비하고 있어요"
            VoicePhase.LISTENING -> "듣고 있어요"
            VoicePhase.PROCESSING -> "처리하고 있어요"
        }
        voiceTranscript?.apply { text = transcript; visibility = if (transcript.isBlank()) View.INVISIBLE else View.VISIBLE }
        if (!showingSettings && ::status.isInitialized) {
            status.text = statusMessage; status.visibility = if (statusMessage.isBlank()) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun handleCommand(text: String, speechEvidence: ResolvedTranscript? = null) {
        if (busy) return
        CommandSafety.blockedReason(text)?.let { tell(it); return }
        val here = Regex("^여기(?:를)?\\s*(?:앞으로\\s*)?(.+?)(?:으로|로)\\s*(?:기억해|저장해)(?:줘)?$").matchEntire(text)
            ?: Regex("^여기\\s*앞으로\\s*(.+?)이라고\\s*해$").matchEntire(text)
        if (here != null) { requestLocation(here.groupValues[1]); return }
        val alias = Regex("^(.+?)(?:를|을)\\s*(.+?)(?:으로|로)\\s*기억해(?:줘)?$").matchEntire(text)
        if (alias != null) {
            launchSafe {
                val resolution = withContext(Dispatchers.IO) { graph.resolver.resolve(alias.groupValues[1]) }
                selectPlace(resolution) { candidate ->
                    launchSafe {
                        val existing = withContext(Dispatchers.IO) { graph.places.get(candidate.id) }
                        if (existing != null) confirmAlias(existing, alias.groupValues[2])
                        else saveDialog(candidate, candidate.name, extraAlias = alias.groupValues[2])
                    }
                }
            }
            return
        }
        launchSafe(command = true) {
            val started = if (speechEvidence != null) voiceStartedAt else SystemClock.elapsedRealtime()
            val times = if (speechEvidence != null) voiceTimes.toMutableMap() else linkedMapOf("T0" to 0L)
            fun stage(value: EngineStage) { tell(engine.stageMessage(value)); if (value == EngineStage.POLICY_CHECK) times["T6"] = SystemClock.elapsedRealtime() - started }
            val prepared = if (speechEvidence != null) engine.prepareSpeech(speechEvidence, useModel, ::stage) else engine.prepare(text, useModel, ::stage)
            val plan = prepared.plan
            val decision = prepared.decision
            when (decision) {
                is PolicyDecision.Ready -> {
                    times["T7"] = SystemClock.elapsedRealtime() - started
                    val outcome = runReady(decision)
                    times["T8"] = SystemClock.elapsedRealtime() - started
                    if (outcome != null) recordCommand(text, speechEvidence, prepared, outcome, started, times)
                }
                is PolicyDecision.Blocked -> {
                    recordCommand(text, speechEvidence, prepared, null, started, times)
                    tell(decision.message)
                    if (decision.appChoices.isNotEmpty()) {
                        AlertDialog.Builder(this@MainActivity).setTitle(decision.message)
                            .setItems(decision.appChoices.map { "${it.name}\n${it.id}" }.toTypedArray()) { _, index ->
                                launchSafe {
                                    when (val chosen = engine.preparePlan(plan, decision.appChoices[index].id).decision) {
                                        is PolicyDecision.Ready -> runReady(chosen)
                                        is PolicyDecision.Blocked -> tell(chosen.message)
                                    }
                                }
                            }.setNegativeButton("취소", null).show()
                        return@launchSafe
                    }
                    val resolution = decision.resolution
                    val setupSlot = resolution?.setupSlot
                    if (resolution?.status == ResolutionStatus.AMBIGUOUS) selectPlace(resolution) { selected ->
                        val command = plan.actions.mapNotNull { it.mediaCommand() }.singleOrNull()
                        launchSafe(command = true) { runReady(PolicyDecision.Ready(selected, command == MediaCommand.RESUME, mediaCommand = command,
                            navigationGoal = plan.actions.filterIsInstance<Action.Navigate>().singleOrNull()?.destination ?: selected.name)) }
                    }
                }
            }
        }
    }

    private fun recordCommand(text: String, speech: ResolvedTranscript?, prepared: PreparedRequest, outcome: AgentOutcome?,
        started: Long, times: Map<String, Long>) {
        val failure = outcome?.failure ?: prepared.failure
        val success = outcome?.execution?.success == true && failure == null
        graph.invocationDebug.write(InvocationTrace(sessionId = UUID.randomUUID().toString(),
            origin = if (speech == null) "TEXT_INPUT" else "MANUAL_MICROPHONE", profile = engine.profile.user,
            interpretation = prepared.interpretation, times = times,
            transitions = listOf("PLANNING", "POLICY_CHECK") + if (outcome != null) listOf("EXECUTING") else emptyList(),
            transcript = text, clarification = prepared.clarification?.prompt.orEmpty(),
            stt = if (speech != null) voiceMetrics else null, agentLoad = if (useModel) graph.planner.lastLoad else null,
            modelInferenceMs = if (useModel) graph.planner.lastInferenceMs else null,
            toolPlan = prepared.plan.toString(), policy = prepared.decision.toString(),
            execution = outcome?.let { "${it.execution}; evidence=${it.evidence}" }.orEmpty(),
            disposition = outcome?.disposition?.name.orEmpty(),
            result = if (success) "SUCCESS" else failure?.state?.name ?: "EXECUTION_FAILED",
            error = failure?.message.orEmpty(), audioFocus = if (speech == null) "NOT_REQUESTED" else "RELEASED_BEFORE_INFERENCE",
            duplicateInvocations = 0, totalMs = SystemClock.elapsedRealtime() - started,
            speech = if (speech != null) graph.speechDiagnostics.last else null,
            selectedModel = graph.planner.selected.key, modelResponse = if (useModel) graph.planner.lastResponse else null))
    }
    private suspend fun runReady(ready: PolicyDecision.Ready, confirmed: Boolean = false): AgentOutcome? {
        if (!confirmed && ready.deviceCommands.any { it.requiresConfirmation }) {
            AlertDialog.Builder(this).setTitle("작업 확인")
                .setMessage("${ready.deviceCommands.joinToString("\n") { it.summary }}\n\n공식 시계 앱에 요청을 전달합니다. 시계 앱의 최종 설정을 확인해 주세요.")
                .setPositiveButton("요청 전달") { _, _ -> launchSafe(command = true) { runReady(ready, confirmed = true) } }
                .setNegativeButton("취소") { _, _ -> tell("작업을 취소했습니다. 요청을 전달하지 않았습니다.") }.show()
            return null
        }
        val outcome = engine.execute(ready, confirmed)
        val result = outcome.execution
        val message = outcome.failure?.message ?: result?.message.orEmpty()
        tell(message)
        if (ready.navigationGoal != null && result?.launched != true) {
            AlertDialog.Builder(this).setTitle("네이버지도 실행 확인")
                .setMessage(message).setPositiveButton("설치 페이지") { _, _ -> navigation.openStore() }
                .setNegativeButton("닫기", null).show()
        }
        return outcome
    }

    private fun configureAccessibility() {
        if (!BuildConfig.UI_AUTOMATION_AVAILABLE) { tell("이 설치판에는 화면 작업 기능이 없습니다. 화면 작업판 APK를 사용해 주세요."); return }
        AlertDialog.Builder(this).setTitle("화면 작업 연결")
            .setMessage("명령한 앱의 화면 내용과 버튼을 읽고, 누르기·입력·스크롤로 작업을 계속합니다. 화면 원문은 기기 안에서 일시적으로 처리하며 전송하거나 저장하지 않습니다. 안내 시작을 확인한 목적지 이름·주소·화면에 명시된 좌표는 선택적으로 암호화해 기억합니다. 설정의 장소 기억을 끌 수 있습니다. 비밀번호·잠금·권한 승인 화면은 조작하지 않습니다. 실행 중 '작업 취소'로 중단할 수 있습니다.\n\nAndroid 설정에서 Local Phone Agent 화면 작업 서비스를 직접 허용해 주세요. 같은 설치 공간의 대상 앱만 사용합니다.")
            .setPositiveButton("동의하고 Android 설정 열기") { _, _ ->
                graph.settings.put("ui_automation_consent", "yes")
                runCatching { startActivity(graph.uiAutomation.settingsIntent()) }
                    .onFailure { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }.setNeutralButton("동의 철회") { _, _ -> graph.settings.put("ui_automation_consent", ""); tell("화면 작업 동의를 철회했습니다.") }
            .setNegativeButton("닫기", null).show()
    }

    private fun selectPlace(resolution: Resolution, selected: (PlaceCandidate) -> Unit) {
        resolution.resolved?.let { selected(it); return }
        if (resolution.status != ResolutionStatus.AMBIGUOUS || resolution.candidates.isEmpty()) { tell(resolution.message); return }
        AlertDialog.Builder(this).setTitle(resolution.message.ifBlank { "어느 장소로 갈까요?" })
            .setItems(resolution.candidates.map { "${it.name}\n${it.address}" }.toTypedArray()) { _, index -> selected(resolution.candidates[index]) }
            .setNegativeButton("취소", null).show()
    }

    private fun registration(defaultName: String) {
        AlertDialog.Builder(this).setTitle(defaultName.ifBlank { "장소 추가" })
            .setItems(arrayOf("주소·장소 검색", "현재 위치 사용", "좌표 직접 입력")) { _, index ->
                when (index) { 0 -> searchDialog(defaultName); 1 -> requestLocation(defaultName); 2 -> manualDialog(defaultName) }
            }.setNegativeButton("닫기", null).show()
    }
    private fun searchDialog(name: String) {
        val field = edit("검색할 장소명 또는 주소", name.takeUnless { PlaceSlots.slotFor(it) != null }.orEmpty())
        AlertDialog.Builder(this).setTitle("장소 검색").setView(padded(field))
            .setPositiveButton("검색") { _, _ -> launchSafe {
                val query = field.text.toString().trim()
                if (query.isBlank()) return@launchSafe
                val resolution = withContext(Dispatchers.IO) { graph.resolver.resolve(query) }
                if (resolution.status == ResolutionStatus.PERMISSION_REQUIRED) {
                    tell("검색 API를 설정하거나 네이버지도에서 장소를 공유해 주세요.")
                    if (!navigation.openSearch(query)) navigation.openStore()
                } else selectPlace(resolution) { candidate -> saveDialog(candidate, name.ifBlank { candidate.name }) }
            } }.setNegativeButton("취소", null).show()
    }

    private fun manualDialog(name: String, address: String = "") {
        val fields = column()
        val placeName = edit("장소명", name); val addr = edit("주소 (선택)", address)
        val latitude = edit("위도").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }
        val longitude = edit("경도").apply { inputType = latitude.inputType }
        listOf(placeName, addr, latitude, longitude).forEach(fields::addView)
        val dialog = AlertDialog.Builder(this).setTitle("좌표 직접 입력").setView(padded(fields))
            .setPositiveButton("확인", null).setNegativeButton("취소", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val coords = runCatching { Coordinates(latitude.text.toString().toDouble(), longitude.text.toString().toDouble()) }.getOrNull()
            if (coords == null || !coords.supportedByNaver() || placeName.text.isBlank()) { tell("장소명과 유효한 국내 위도·경도를 입력해 주세요."); return@setOnClickListener }
            dialog.dismiss()
            saveDialog(PlaceCandidate("manual", placeName.text.toString().trim(), coords, addr.text.toString().trim(), "USER", PlaceSource.MANUAL), name)
        } }; dialog.show()
    }

    private fun saveDialog(candidate: PlaceCandidate, defaultName: String, existing: UserPlace? = null, extraAlias: String = "") {
        val fields = column()
        fields.addView(label("${candidate.name}\n${candidate.address}\n${candidate.coordinates.latitude}, ${candidate.coordinates.longitude}", 14))
        val name = edit("무엇으로 저장할까요?", defaultName.ifBlank { candidate.name })
        val aliases = edit("별칭 (쉼표로 구분)", (existing?.aliases.orEmpty() + listOfNotNull(extraAlias.takeIf { it.isNotBlank() })).joinToString(", "))
        fields.addView(name); fields.addView(aliases)
        fields.addView(label("저장 확인을 누르면 이 기기의 내 장소에 기록됩니다.", 13))
        val dialog = AlertDialog.Builder(this).setTitle("장소 저장 확인").setView(padded(fields))
            .setPositiveButton("저장 확인", null).setNegativeButton("취소", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val canonical = name.text.toString().trim()
            val slot = PlaceSlots.slotFor(canonical)
            val id = slot ?: existing?.id ?: UUID.randomUUID().toString()
            val aliasList = (aliases.text.toString().split(',').map(String::trim).filter(String::isNotBlank) + PlaceSlots.aliases[slot].orEmpty()).distinct()
            launchSafe {
                val previous = withContext(Dispatchers.IO) { graph.places.get(id) }
                val now = System.currentTimeMillis()
                val place = runCatching { UserPlace(id, canonical, aliasList, candidate.coordinates, candidate.address, candidate.provider, candidate.source, previous?.createdAt ?: now, now) }.getOrNull()
                if (place == null) { tell("장소명이나 별칭 길이를 확인해 주세요."); return@launchSafe }
                val save: () -> Unit = {
                    launchSafe { withContext(Dispatchers.IO) {
                        graph.places.saveConfirmed(place)
                        if (existing != null && existing.id != id) graph.places.delete(existing.id)
                    }; dialog.dismiss(); render(); tell("${place.canonicalName} 장소를 저장했습니다.") }
                }
                if (previous != null && previous.id != existing?.id) {
                    AlertDialog.Builder(this@MainActivity).setTitle("기존 장소 바꾸기")
                        .setMessage("${previous.canonicalName}의 기존 위치를 다음 위치로 바꿀까요?\n${candidate.name}\n${candidate.address}")
                        .setPositiveButton("바꾸기") { _, _ -> save() }.setNegativeButton("취소", null).show()
                } else save()
            }
        } }; dialog.show()
    }

    private fun askLocationName() {
        val field = edit("집 / 회사 / 공장 등")
        AlertDialog.Builder(this).setTitle("현재 위치를 무엇으로 기억할까요?").setView(padded(field))
            .setPositiveButton("위치 확인") { _, _ -> requestLocation(field.text.toString().trim()) }.setNegativeButton("취소", null).show()
    }
    private fun requestLocation(name: String) {
        if (name.isBlank()) { askLocationName(); return }
        if (name.length > 160) { tell("장소명은 160자 이내로 입력해 주세요."); return }
        pendingLocationName = name
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) captureLocation(name)
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private fun captureLocation(name: String) = launchSafe {
        tell("현재 위치를 확인하고 있습니다…")
        val location = CurrentLocation.get(this@MainActivity)
        if (location == null || !location.hasAccuracy() || location.accuracy > 100f) { tell("정확한 위치를 얻지 못했습니다. GPS를 켜거나 주소 검색으로 등록해 주세요."); return@launchSafe }
        val coords = Coordinates(location.latitude, location.longitude)
        if (!coords.supportedByNaver()) { tell("네이버지도 지원 범위 밖의 위치입니다."); return@launchSafe }
        saveDialog(PlaceCandidate("current", name, coords, "", "ANDROID", PlaceSource.CURRENT_LOCATION), name)
    }

    private fun addAlias(place: UserPlace) {
        val field = edit("예: 단골 주유소")
        AlertDialog.Builder(this).setTitle("${place.canonicalName} 별칭 추가").setView(padded(field))
            .setPositiveButton("내용 확인") { _, _ -> confirmAlias(place, field.text.toString().trim()) }.setNegativeButton("취소", null).show()
    }
    private fun confirmAlias(place: UserPlace, alias: String) {
        if (alias.isBlank() || alias.length > 160) { tell("별칭을 160자 이내로 입력해 주세요."); return }
        launchSafe {
            val collisions = withContext(Dispatchers.IO) { graph.places.all() }.filter { it.id != place.id &&
                (it.aliases + it.canonicalName + it.id).any { other -> PlaceText.variants(alias).contains(PlaceText.normalize(other)) } }
            AlertDialog.Builder(this@MainActivity).setTitle("별칭 변경 확인")
                .setMessage("${place.canonicalName}\n현재: ${place.aliases.joinToString()}\n추가: $alias" +
                    if (collisions.isEmpty()) "" else "\n다른 장소에도 같은 별칭이 있어 이동할 때 선택이 필요합니다.")
                .setPositiveButton("추가 확인") { _, _ -> launchSafe {
                    withContext(Dispatchers.IO) { graph.places.saveConfirmed(place.copy(aliases = (place.aliases + alias).distinct(), updatedAt = System.currentTimeMillis())) }
                    render(); tell("별칭을 추가했습니다.")
                } }.setNegativeButton("취소", null).show()
        }
    }
    private fun deletePlace(place: UserPlace) {
        AlertDialog.Builder(this).setTitle("${place.canonicalName} 삭제")
            .setMessage("이 장소와 별칭을 기기에서 삭제할까요?")
            .setPositiveButton("삭제") { _, _ -> launchSafe { withContext(Dispatchers.IO) { graph.places.delete(place.id) }; render(); tell("장소를 삭제했습니다.") } }
            .setNegativeButton("취소", null).show()
    }

    private fun receiveShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // Consume once. External intents can only open a review, never execute or save.
        intent.removeExtra(Intent.EXTRA_TEXT)
        when (val result = NaverShareParser.parse(text)) {
            is ShareResult.CoordinatesFound -> saveDialog(result.candidate, "")
            is ShareResult.NeedsSelection -> {
                tell(result.message)
                val fields = column()
                val name = edit("장소명", result.name)
                fields.addView(name)
                fields.addView(label(result.message, 14))
                AlertDialog.Builder(this).setTitle("공유한 장소 확인").setView(padded(fields))
                    .setPositiveButton("장소 검색") { _, _ -> searchDialog(name.text.toString().trim()) }
                    .setNeutralButton("현재 위치") { _, _ -> requestLocation(name.text.toString().trim()) }
                    .setNegativeButton("직접 입력") { _, _ -> manualDialog(name.text.toString().trim()) }.show()
            }
            is ShareResult.Rejected -> tell(result.message)
        }
    }

    private fun searchSettings() = launchSafe {
        val fields = column()
        fields.addView(label("설정하면 미등록 장소 검색어가 네이버 지역 검색 API로 전송됩니다. 집·회사 등 개인 별칭은 외부 검색하지 않습니다.", 14))
        val id = edit("NAVER Client ID", withContext(Dispatchers.IO) { graph.settings.get("naver_client_id") })
        val secret = edit("NAVER Client Secret", withContext(Dispatchers.IO) { graph.settings.get("naver_client_secret") })
            .apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        fields.addView(id); fields.addView(secret)
        AlertDialog.Builder(this@MainActivity).setTitle("네이버 장소 검색").setView(padded(fields))
            .setPositiveButton("검색 사용") { _, _ -> launchSafe {
                withContext(Dispatchers.IO) {
                    graph.settings.put("naver_client_id", id.text.toString().trim())
                    graph.settings.put("naver_client_secret", secret.text.toString().trim())
                    graph.settings.put("search_enabled", "yes")
                }; tell("외부 장소 검색을 설정했습니다.")
            } }.setNeutralButton("검색 끄기") { _, _ -> launchSafe { withContext(Dispatchers.IO) { graph.settings.put("search_enabled", "") }; tell("외부 검색을 껐습니다.") } }
            .setNegativeButton("취소", null).show()
    }
    private fun configureMedia() {
        if (!media.availableInThisBuild) { tell(media.permissionMessage()); return }
        if (!media.hasPermission()) {
            AlertDialog.Builder(this).setTitle("음악 제어 연결")
                .setMessage("Android의 알림 접근 허용이 필요합니다. 앱은 음악 재생 세션 제어에만 사용하며 알림 내용은 수집하거나 저장하지 않습니다.")
                .setPositiveButton("설정 열기") { _, _ -> startActivity(media.permissionIntent()) }.setNegativeButton("취소", null).show()
            return
        }
        val sessions = media.controllers()
        if (sessions.isEmpty()) { tell("음악 앱에서 곡을 한 번 재생한 뒤 다시 연결해 주세요."); return }
        AlertDialog.Builder(this).setTitle("음악 앱 선택")
            .setItems(sessions.map { controller -> runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(controller.packageName, 0)).toString()
            }.getOrDefault(controller.packageName) }.toTypedArray()) { _, index -> media.choose(sessions[index].packageName); tell("음악 앱을 연결했습니다.") }
            .setNegativeButton("취소", null).show()
    }
    private fun renderModelState(states: Map<LocalModelId, ModelStatus>) {
        renderingModels = true
        try {
            modelChoices.forEach { (id, view) ->
                val value = states.getValue(id); val info = graph.embeddedModels.info(id)
                val name = when (value.state) {
                    ModelRuntimeState.INSTALLED -> "설치됨"
                    ModelRuntimeState.LOADING -> "불러오는 중…"
                    ModelRuntimeState.READY -> "준비됨"
                    ModelRuntimeState.CURRENT -> "현재 사용 중"
                    ModelRuntimeState.FAILED -> "실패"
                }
                view.text = "${info.name} · $name${if (value.selected && value.state != ModelRuntimeState.CURRENT) " · 선택됨" else ""}\n${info.quantization} · ${info.bytes / 1048576}MB${if (value.error.isNotBlank()) "\n${value.error}" else ""}"
                view.isChecked = value.selected
                view.isEnabled = !busy && states.values.none { it.state == ModelRuntimeState.LOADING }
            }
        } finally { renderingModels = false }
    }
    private fun chooseModel(id: LocalModelId) {
        if (id == LocalModelId.FUNCTIONGEMMA && !graph.planner.gemmaTermsAccepted) { modelTerms { chooseModel(id) }; return }
        launchSafe {
            renderModelState(graph.planner.status.value)
            tell("${graph.embeddedModels.info(id).name} 준비와 실제 추론 검사 중…")
            val result = graph.planner.select(id)
            tell(result.message)
            graph.planner.releaseAfterIdle()
            renderModelState(graph.planner.status.value)
        }
    }
    private fun modelTerms(accepted: (() -> Unit)? = null) {
        val content = listOf("Gemma-Terms.txt", "Gemma-Prohibited-Use.txt", "Qwen-Apache-2.0.txt", "llama.cpp-MIT.txt").joinToString("\n\n") { filename ->
            assets.open("licenses/$filename").bufferedReader().use { it.readText() }
        }
        val scroll = ScrollView(this).apply { addView(padded(label(
            "이 앱의 Gemma 모델 사용에는 아래 Gemma 약관과 금지 사용 정책이 적용됩니다. 동의하면 사용 제한을 준수해야 합니다. Qwen과 실행 엔진의 라이선스도 포함되어 있습니다.\n\n$content", 13))) }
        AlertDialog.Builder(this).setTitle("내장 모델 사용 약관").setView(scroll)
            .setPositiveButton(if (graph.planner.gemmaTermsAccepted) "닫기" else "동의하고 사용") { _, _ ->
                graph.planner.acceptGemmaTerms(); accepted?.invoke()
            }.setNegativeButton("취소", null).show()
    }

    private fun launchSafe(command: Boolean = false, block: suspend () -> Unit) {
        lifecycleScope.launch {
            val commandLease = if (command) UUID.randomUUID().toString() else null
            if (commandLease != null && !graph.invocationArbiter.acquire(commandLease)) { tell("다른 음성 작업이 진행 중입니다."); return@launch }
            activeJobs++
            if (command) setVoicePhase(VoicePhase.PROCESSING)
            try { block() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { tell("작업을 완료하지 못했습니다. 입력과 기기 설정을 확인해 주세요.") }
            finally {
                commandLease?.let(graph.invocationArbiter::release)
                if (command) graph.planner.releaseAfterIdle()
                activeJobs--; if (command) setVoicePhase(VoicePhase.IDLE)
                renderModelState(graph.planner.status.value)
            }
        }
    }
    private fun tell(message: String) {
        statusMessage = message
        if (::status.isInitialized) { status.text = message; status.visibility = View.VISIBLE }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun padded(view: View) = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)); addView(view) }
    private fun edit(hintText: String, value: String = "") = EditText(this).apply {
        hint = hintText; setText(value); textSize = 16f; setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextColor(Color.rgb(27, 43, 37)); background = rounded(Color.WHITE)
        maxLines = 3
    }
    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(Color.rgb(47, 63, 55)); setPadding(0, dp(5), 0, dp(5))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun addText(value: String, size: Int, color: Int? = null, bold: Boolean = false) = label(value, size, bold).also {
        color?.let(it::setTextColor); body.addView(it)
    }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun button(title: String, action: () -> Unit) { body.addView(smallButton(title, action), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
    private fun smallButton(title: String, action: () -> Unit) = Button(this).apply { text = title; isAllCaps = false; setOnClickListener { if (!busy) action() } }
    private fun rowButtons(vararg buttons: Pair<String, () -> Unit>) {
        val row = LinearLayout(this); buttons.forEach { (text, action) -> row.addView(smallButton(text, action), LinearLayout.LayoutParams(0, -2, 1f)) }; body.addView(row)
    }
    private fun space() { body.addView(View(this), LinearLayout.LayoutParams(1, dp(20))) }
}
