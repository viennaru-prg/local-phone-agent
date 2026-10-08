package dev.localphone.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import dev.localphone.agent.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** History stays inside this profile until the user explicitly copies or exports it. */
class InvocationDebugActivity : ComponentActivity() {
    private val graph get() = application as AgentApplication
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    private var selectedId: String? = null
    private var pendingExport: String? = null
    private val saveJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = pendingExport; pendingExport = null
        if (uri != null && data != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                graph.invocationDebug.awaitPersistence()
                checkNotNull(contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { it.write(data) }
            } }
            Toast.makeText(this@InvocationDebugActivity, if (result.isSuccess) "기록 JSON을 저장했습니다." else "기록을 저장하지 못했습니다.", Toast.LENGTH_LONG).show()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        selectedId = savedInstanceState?.getString("selectedId")
    }
    override fun onResume() { super.onResume(); render() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("selectedId", selectedId); super.onSaveInstanceState(outState) }
    private fun render() {
        val records = graph.invocationDebug.readHistory()
        val group = graph.settings.get("diagnostic_comparison_group")
        val comparison = InvocationComparisons.compare(records, group)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(8)) }
        fun text(value: String) = TextView(this).apply { text = value; textSize = 14f }
        fun button(label: String, idValue: Int, action: () -> Unit) = Button(this).apply {
            text = label; id = idValue; isAllCaps = false; setOnClickListener { action() }
        }
        layout.addView(text("음성 호출 기록 · 비교"))
        layout.addView(Switch(this).apply {
            id = R.id.diagnostic_enabled; text = "상세 호출 기록"; isChecked = graph.invocationDebug.detailsEnabled()
            setOnCheckedChangeListener { _, checked -> graph.settings.put("stt_diagnostics", if (checked) "yes" else "no") }
        })
        layout.addView(text("최근 최대 30건을 앱 내부에 암호화해 보관합니다. 음성 파일은 저장하지 않습니다. 명령·후보·모델 입력은 상세 기록을 켠 호출에 포함됩니다."))
        layout.addView(button("비교 기록 시작", R.id.diagnostic_start_comparison) {
            graph.settings.put("stt_diagnostics", "yes"); graph.settings.put("diagnostic_comparison_group", UUID.randomUUID().toString())
            selectedId = null; render()
            Toast.makeText(this, "중앙 버튼 → 뒷면 호출로 같은 명령을 말한 뒤 이 화면으로 돌아오세요.", Toast.LENGTH_LONG).show()
        })
        layout.addView(text(if (group.isBlank()) "비교 기록 시작을 누른 뒤 중앙 버튼과 자동 호출로 같은 명령을 말하세요."
            else "비교 기록 중: 중앙 버튼과 자동 호출을 각각 실행한 뒤 새로고침하세요.\n${comparison.status}: ${comparison.note}"))
        val row = LinearLayout(this)
        row.addView(button("새로고침", R.id.diagnostic_refresh) { render() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("비교 결과 복사", R.id.diagnostic_copy_comparison) {
            val selected = records.filter { it.sessionId == comparison.manualId || it.sessionId == comparison.automaticId }
            val output = gson.toJson(mapOf("comparison" to comparison, "records" to selected))
            if (output.length > 160_000) Toast.makeText(this, "긴 기록은 ‘기록 JSON 저장’을 사용하세요.", Toast.LENGTH_LONG).show()
            else { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Local Phone Agent 비교", output))
                Toast.makeText(this, "비교 결과와 두 호출 기록을 복사했습니다.", Toast.LENGTH_SHORT).show() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(row)
        val fileRow = LinearLayout(this)
        fileRow.addView(button("기록 JSON 저장", R.id.diagnostic_export) {
            pendingExport = gson.toJson(mapOf("schemaVersion" to 1, "appVersion" to BuildConfig.VERSION_NAME,
                "scope" to "User-selected local diagnostics; no automatic upload", "comparison" to comparison, "records" to records))
            saveJson.launch("LocalPhoneAgent-voice-diagnostics-${System.currentTimeMillis()}.json")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fileRow.addView(button("기록 지우기", R.id.diagnostic_clear) {
            graph.invocationDebug.clear(); graph.settings.put("diagnostic_comparison_group", ""); selectedId = null; render()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(fileRow)
        val detail = text("").apply { id = R.id.diagnostic_details; setTextIsSelectable(true) }
        val spinner = Spinner(this).apply { id = R.id.diagnostic_history }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, records.map { trace ->
            val source = when (trace.origin) { "MANUAL_MICROPHONE" -> "중앙 음성"; "TEXT_INPUT" -> "텍스트"; else -> "자동 앱 호출" }
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(trace.invokedAt))
            "$time · $source · ${trace.result}"
        })
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                records.getOrNull(position)?.let { selectedId = it.sessionId; detail.text = describe(it) }
            }
        }
        layout.addView(spinner)
        val index = records.indexOfFirst { it.sessionId == selectedId }.coerceAtLeast(0)
        if (records.isEmpty()) detail.text = "아직 호출 기록이 없습니다."
        else { spinner.setSelection(index); detail.text = describe(records[index]) }
        layout.addView(ScrollView(this).apply { addView(detail) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
    }
    private fun describe(trace: InvocationTrace): String = buildString {
        appendLine("현재 설치: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) / ${ProfileScope(this@InvocationDebugActivity).user}")
        appendLine("호출: ${trace.sessionId} / ${trace.origin} / ${trace.result}")
        appendLine("선택 AI: ${trace.selectedModel}; enabled=${trace.modelEnabled}; interpretation=${trace.interpretation}")
        trace.speech?.let { speech ->
            appendLine("ASR: ${speech.recognition.engine}; source=${speech.recognition.source}")
            speech.recognition.hypotheses.forEach { appendLine("RAW #${it.rank + 1}: ${it.text} / confidence=${it.acousticConfidence ?: "미제공"}") }
            appendLine("RESOLVED: ${speech.resolution.selectedText}; clarification=${speech.resolution.requiresClarification}")
            appendLine("Evidence: ${speech.resolution.evidence.joinToString()}")
        }
        appendLine("Error: ${trace.error}")
        appendLine("이 자동 호출 기록만으로 런처와 실제 뒷면 센서를 구분할 수는 없습니다.")
        if (trace.audit == null) appendLine("상세 기록을 켜기 전 호출입니다. 비교 기록 시작 후 다시 실행하세요.")
        appendLine("\n" + gson.toJson(trace))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
