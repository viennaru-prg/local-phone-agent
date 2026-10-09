package dev.localphone.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import dev.localphone.agent.access.AgentAccessibilityService
import dev.localphone.agent.access.AppIndex
import dev.localphone.core.*
import kotlinx.coroutines.*

/** Plain settings screen: permissions, model, AI notes, learned paths and a typed test command. */
class SettingsActivity : Activity() {
    private val scope = MainScope()
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(40)) }
        setContentView(ScrollView(this).apply { addView(root) })
        handleDebug(intent)
    }

    override fun onResume() {
        super.onResume(); render()
        if (app.updates.hasPending && app.updates.canInstall()) {
            scope.launch { runCatching { withContext(Dispatchers.IO) { app.updates.installPending() } }
                .onFailure { toast(it.message ?: "업데이트 설치를 열지 못했습니다") } }
        }
    }

    /** Debug-build adb hooks; runs for a fresh launch (onCreate) and a re-delivered intent (onNewIntent). */
    private fun handleDebug(intent: Intent) {
        // Debug builds: `am start -n .../.SettingsActivity --es eval <model.gguf> [--ei threads 6] [--ez gpu true]`
        val eval = if (BuildConfig.ADB_GOALS) intent.getStringExtra("eval") else null
        if (eval != null) {
            intent.removeExtra("eval")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Eval options are temporary: the user's real settings are restored afterwards.
            val saved = listOf(app.prefs.modelFile, app.prefs.threads, app.prefs.gpu, app.prefs.think, app.prefs.withNote, app.prefs.thinkChars)
            if (eval.isNotBlank()) app.prefs.modelFile = eval
            intent.getIntExtra("threads", 0).takeIf { it > 0 }?.let { app.prefs.threads = it }
            if (intent.hasExtra("gpu")) app.prefs.gpu = intent.getBooleanExtra("gpu", true)
            app.prefs.think = intent.getBooleanExtra("think", false)
            app.prefs.withNote = intent.getBooleanExtra("note", true)
            intent.getIntExtra("think_chars", 0).takeIf { it > 0 }?.let { app.prefs.thinkChars = it }
            scope.launch {
                try { runEval() } finally {
                    app.prefs.modelFile = saved[0] as String; app.prefs.threads = saved[1] as Int; app.prefs.gpu = saved[2] as Boolean
                    app.prefs.think = saved[3] as Boolean; app.prefs.withNote = saved[4] as Boolean; app.prefs.thinkChars = saved[5] as Int
                    app.llm.unload()
                }
            }
        }
        // Debug builds: `--es add_place "회사,직장|서울특별시 중구 세종대로 110"` registers a place.
        val addPlace = if (BuildConfig.ADB_GOALS) intent.getStringExtra("add_place_b64") else null
        if (addPlace != null) {
            intent.removeExtra("add_place_b64")
            val (names, address) = String(android.util.Base64.decode(addPlace, android.util.Base64.DEFAULT)).split('|', limit = 2)
            scope.launch { android.util.Log.i("AgentEval", "add_place: " + addPlace(names.split(',').map(String::trim), address.trim())); render() }
        }
        if (BuildConfig.ADB_GOALS && intent.hasExtra("test_mode")) {
            app.prefs.testMode = intent.getBooleanExtra("test_mode", false)
            intent.removeExtra("test_mode")
            android.util.Log.i("AgentEval", "test_mode=${app.prefs.testMode}")
        }
        // Debug builds: restore default AI options after older eval runs changed them.
        if (BuildConfig.ADB_GOALS && intent.getBooleanExtra("reset_ai", false)) {
            intent.removeExtra("reset_ai")
            app.prefs.modelFile = ""; app.prefs.withNote = true; app.prefs.think = false; app.prefs.gpu = true
            android.util.Log.i("AgentEval", "AI options reset: model=${app.llm.modelFile()?.name} note=true think=false gpu=true")
        }
        // Debug builds: `am start -n .../.SettingsActivity --es bench <model.gguf> --ei threads 6`
        val bench = if (BuildConfig.ADB_GOALS) intent.getStringExtra("bench") else null
        if (bench != null) {
            intent.removeExtra("bench")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (bench.isNotBlank()) app.prefs.modelFile = bench
            intent.getIntExtra("threads", 0).takeIf { it > 0 }?.let { app.prefs.threads = it }
            if (intent.hasExtra("gpu")) app.prefs.gpu = intent.getBooleanExtra("gpu", true)
            scope.launch {
                app.llm.unload()
                android.util.Log.i("AgentBench", "model=${app.prefs.modelFile} threads=${app.prefs.threads} gpu=${app.prefs.gpu}")
                android.util.Log.i("AgentBench", benchmark())
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleDebug(intent) }

    private fun render() {
        root.removeAllViews()
        header("업데이트 · ${BuildConfig.VERSION_NAME}")
        val updateStatus = note("GitHub에서 새 버전을 받아 설치합니다.")
        val updateButton = Button(this).apply { text = "GitHub 업데이트 확인" }
        root.addView(updateButton)
        updateButton.setOnClickListener {
            updateButton.isEnabled = false
            updateStatus.text = "업데이트 확인 중…"
            scope.launch {
                try {
                    if (app.updates.hasPending) {
                        if (app.updates.canInstall()) withContext(Dispatchers.IO) { app.updates.installPending() }
                        else startActivity(app.updates.permissionIntent())
                    } else {
                        val available = app.updates.check()
                        if (available == null) updateStatus.text = "최신 버전입니다."
                        else {
                            app.updates.download(available) { percent ->
                                runOnUiThread { updateStatus.text = "${available.update.versionName} 다운로드 $percent%\n${available.release.notes.take(1200)}" }
                            }
                            updateStatus.text = "파일 검증 완료. Android 설치 화면을 엽니다."
                            if (app.updates.canInstall()) withContext(Dispatchers.IO) { app.updates.installPending() }
                            else { updateStatus.text = "이 앱의 '이 출처 허용'을 켜고 돌아오면 설치 화면이 열립니다."; startActivity(app.updates.permissionIntent()) }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { updateStatus.text = e.message ?: "업데이트 확인 실패" }
                finally { updateButton.isEnabled = true }
            }
        }
        header("권한")
        val a11y = AgentAccessibilityService.instance != null
        row("화면 조작(접근성): " + if (a11y) "켜짐" else "꺼짐 — 눌러서 켜기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        row("마이크: " + if (mic) "허용됨" else "허용 필요 — 눌러서 요청") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        header("로컬 AI 모델")
        val models = app.modelsDir.listFiles { f -> f.name.endsWith(".gguf") }?.sortedBy { it.name }.orEmpty()
        val current = app.llm.modelFile()
        note("모델 폴더: ${app.modelsDir.absolutePath}")
        if (models.isEmpty()) note("GGUF 모델 파일이 없습니다. PC에서 scripts/dev.ps1 push-model 로 넣어 주세요.")
        val group = RadioGroup(this)
        models.forEach { f ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = "${f.name} (${f.length() / 1_048_576} MB)"; isChecked = f == current
                setOnClickListener { app.prefs.modelFile = f.name; scope.launch { app.llm.unload() } }
            })
        }
        root.addView(group)
        toggle("GPU(Adreno)로 실행", app.prefs.gpu) { app.prefs.gpu = it; scope.launch { app.llm.unload() } }
        number("CPU 스레드", app.prefs.threads) { app.prefs.threads = it.coerceIn(1, 8); scope.launch { app.llm.unload() } }
        number("사용 후 모델 유지(분)", app.prefs.keepLoadedMinutes) { app.prefs.keepLoadedMinutes = it.coerceIn(1, 240) }
        val bench = note("")
        button("모델 속도 측정") { bench.text = "측정 중…"; scope.launch { bench.text = benchmark() } }

        header("장소 (길안내 바로 시작)")
        note("장소 등록은 선택입니다. 등록하면 링크로 빠르게 시도하고, 없으면 네이버 지도에서 장소를 찾습니다. 실제 안내 화면이 확인되어야 완료합니다.")
        app.places.all().forEach { p ->
            row("${p.name}${if (p.aliases.isNotEmpty()) " (${p.aliases.joinToString()})" else ""} — ${p.address}\n" +
                "좌표 %.5f, %.5f  (눌러서 삭제)".format(p.lat, p.lng)) { app.places.remove(p.name); render() }
        }
        val placeName = EditText(this).apply { hint = "이름 (예: 회사, 직장)"; textSize = 15f }
        val placeAddress = EditText(this).apply { hint = "주소 (예: 경기 수원시 권선구 …)"; textSize = 15f }
        root.addView(placeName); root.addView(placeAddress)
        val placeStatus = note("")
        button("장소 등록") {
            val names = placeName.text.toString().split(',', '/').map(String::trim).filter(String::isNotEmpty)
            val address = placeAddress.text.toString().trim()
            if (names.isEmpty() || address.isEmpty()) { placeStatus.text = "이름과 주소를 입력해 주세요."; return@button }
            placeStatus.text = "주소 확인 중…"
            scope.launch { placeStatus.text = addPlace(names, address); render() }
        }

        header("동작")
        toggle("판단 설명 생성 (끄면 더 빠름)", app.prefs.withNote) { app.prefs.withNote = it }
        toggle("결과를 음성으로 말하기", app.prefs.speak) { app.prefs.speak = it }
        toggle("성공한 방법 기억해서 다음에 빠르게 실행", app.prefs.useRecipes) { app.prefs.useRecipes = it }

        header("AI 메모 (앱 사용 습관·위치 힌트)")
        val notes = EditText(this).apply {
            setText(app.prefs.notes); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 5; textSize = 14f
        }
        root.addView(notes)
        button("메모 저장") { app.prefs.notes = notes.text.toString(); toast("저장했습니다") }

        header("기억한 방법")
        val recipes = app.recipes.all()
        if (recipes.isEmpty()) note("아직 없습니다. 성공한 방법은 여기에 나타나고, 직접 확인한 방법만 재사용합니다.")
        recipes.sortedByDescending { it.lastUsed.coerceAtLeast(it.created) }.forEach { r ->
            row("“${r.goal}” — ${if (r.confirmed) "확인됨" else "확인 전(재실행 안 함)"}, ${r.steps.size}단계, ${r.uses}회 사용\n" + r.steps.joinToString(" → ") { s ->
                when (s.op) { "open_app" -> "앱:${s.app}"; "type" -> "입력:${s.text}"; "scroll" -> "스크롤"; "back" -> "뒤로"; "media" -> s.key; else -> s.label }
            } + if (r.confirmed) "\n(눌러서 삭제)" else "\n(눌러서 기억 여부 선택)") {
                if (r.confirmed) { app.recipes.remove(r.key); render() }
                else android.app.AlertDialog.Builder(this).setTitle("결과가 맞으면 기억하기").setMessage(r.goal)
                    .setPositiveButton("기억하기") { _, _ -> app.recipes.confirm(r.goal); render() }
                    .setNegativeButton("삭제") { _, _ -> app.recipes.remove(r.key); render() }.setNeutralButton("닫기", null).show()
            }
        }

        header("시험")
        val goal = EditText(this).apply { hint = "예: 회사로 안내해줘"; textSize = 15f }
        root.addView(goal)
        button("이 명령 실행") { AgentService.run(this, goal.text.toString()); moveTaskToBack(true) }
        val spaces = note("")
        button("열 수 있는 앱 공간 확인 (보안 폴더 포함 여부)") {
            val all = AppIndex(this).all()
            spaces.text = all.groupBy { it.space.ifEmpty { "기본" } }.entries.joinToString("\n") { (space, list) ->
                "$space: ${list.size}개 — " + list.take(12).joinToString(", ") { it.label }
            }
        }
    }

    private suspend fun addPlace(names: List<String>, address: String): String {
        val coords = withContext(Dispatchers.IO) { runCatching { dev.localphone.agent.access.AndroidTools.geocode(this@SettingsActivity, address) }.getOrNull() }
            ?: return "주소를 좌표로 바꾸지 못했어요. 주소를 더 자세히 적거나 인터넷 연결을 확인해 주세요."
        app.places.put(Place(names.first(), names.drop(1), address, coords.first, coords.second))
        return "'${names.first()}' 등록: %.5f, %.5f".format(coords.first, coords.second)
    }

    /** Runs the decision cases in assets/eval-cases.json with the selected model; results go to logcat. */
    private suspend fun runEval() {
        val log = { s: String -> android.util.Log.i("AgentEval", s) }
        app.llm.unload()
        log("model=${app.prefs.modelFile} threads=${app.prefs.threads} gpu=${app.prefs.gpu} note=${app.prefs.withNote} think=${app.prefs.think}/${app.prefs.thinkChars}")
        val cases = Evaluator.load(assets.open("eval-cases.json").bufferedReader().readText())
        var pass = 0; var total = 0L
        for (case in cases) {
            val view = Evaluator.view(case)
            val t0 = System.currentTimeMillis()
            val note = app.prefs.withNote
            // The harness rejects chatter before the model is asked, exactly as the agent does.
            val auto = Harness.preDecide(case.goal, view)?.action as? AgentAction.Click
            val raw = if (GoalText.isChatter(case.goal) && Evaluator.history(case).isEmpty()) """{"action":"fail","reason":"harness"}"""
            else if (auto != null) """{"action":"click","id":${auto.id}}""" else try {
                app.llm.decide(Prompts.step(case.goal, app.prefs.noteLines, Evaluator.history(case), view, note), ActionGrammar.forView(view, note))
            } catch (e: Exception) { "ERROR ${e.message}" }
            val ms = System.currentTimeMillis() - t0; total += ms
            val action = runCatching { ActionParser.parse(raw, view).action }.getOrNull()
            val ok = action != null && Evaluator.judge(case, view, action)
            if (ok) pass++
            log("${if (ok) "PASS" else "FAIL"} ${ms}ms | ${case.name} | ${action?.describe(view) ?: raw} | want ${case.expect}" +
                if (app.prefs.think) " | 생각: ${app.llm.lastThought.replace('\n', ' ').take(120)}" else "")
        }
        log("SUMMARY $pass/${cases.size} passed, avg ${total / cases.size}ms per decision (first includes load)")
    }

    private suspend fun benchmark(): String = try {
        val snap = Snapshot("bench", "테스트", listOf(RawNode("r", -1, bounds = Bounds(0, 0, 1080, 2400))) +
            (1..30).map { RawNode("r.$it", 0, text = "항목 $it 서울특별시 강남구", clickable = true, bounds = Bounds(0, it * 70, 1080, it * 70 + 60)) }, 1080, 2400)
        val view = ScreenCompactor.compact(snap)
        val t0 = System.currentTimeMillis()
        val out = app.llm.decide(Prompts.step("항목 7 눌러줘", app.prefs.noteLines, emptyList(), view), ActionGrammar.forView(view))
        val first = System.currentTimeMillis() - t0
        val s1 = app.llm.lastStats
        val t1 = System.currentTimeMillis()
        app.llm.decide(Prompts.step("항목 7 눌러줘", app.prefs.noteLines, listOf(HistoryLine("click [3]", "화면 바뀜")), view), ActionGrammar.forView(view))
        val second = System.currentTimeMillis() - t1
        val s2 = app.llm.lastStats
        "첫 호출 ${first}ms (로드 ${app.llm.lastLoadMs}ms, 프롬프트 ${s1?.promptTokens}토큰, 처리 ${s1?.prefillMs}ms, 생성 ${s1?.outputTokens}토큰 ${s1?.generationMs}ms)\n" +
            "두 번째 ${second}ms (재사용 ${s2?.reusedTokens}토큰, 처리 ${s2?.prefillMs}ms, 생성 ${s2?.generationMs}ms)\n출력: $out"
    } catch (e: Exception) { "실패: ${e.message}" }

    // ---- tiny view helpers ----
    private fun header(text: String) = root.addView(TextView(this).apply {
        this.text = text; textSize = 18f; setTextColor(Color.rgb(31, 111, 235)); setPadding(0, dp(22), 0, dp(6))
    })
    private fun note(text: String) = TextView(this).apply { this.text = text; textSize = 13f; setTextColor(Color.DKGRAY) }.also { root.addView(it) }
    private fun row(text: String, onClick: () -> Unit) = root.addView(TextView(this).apply {
        this.text = text; textSize = 15f; setPadding(0, dp(10), 0, dp(10)); setOnClickListener { onClick() }
    })
    private fun button(text: String, onClick: () -> Unit) = root.addView(Button(this).apply { this.text = text; setOnClickListener { onClick() } })
    private fun toggle(text: String, value: Boolean, onChange: (Boolean) -> Unit) = root.addView(Switch(this).apply {
        this.text = text; isChecked = value; textSize = 15f; setOnCheckedChangeListener { _, v -> onChange(v) }
    })
    private fun number(label: String, value: Int, onChange: (Int) -> Unit) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        line.addView(TextView(this).apply { text = "$label: "; textSize = 15f })
        line.addView(EditText(this).apply {
            setText(value.toString()); inputType = InputType.TYPE_CLASS_NUMBER; minEms = 3
            setOnFocusChangeListener { _, focused -> if (!focused) text.toString().toIntOrNull()?.let(onChange) }
        })
        root.addView(line)
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
