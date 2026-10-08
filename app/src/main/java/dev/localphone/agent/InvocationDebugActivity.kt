package dev.localphone.agent

import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import com.google.gson.GsonBuilder
import dev.localphone.agent.runtime.ProfileScope

/** Deliberately absent from the launcher and ordinary voice flow. */
class InvocationDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val graph = application as AgentApplication
        val trace = graph.invocationDebug.read()
        val speech = trace?.speech ?: graph.speechDiagnostics.last
        val speechText = if (speech == null) "" else buildString {
            appendLine("ASR: ${speech.recognition.engine} / on-device=${speech.recognition.onDevice} / ${speech.recognition.locale}")
            appendLine("Source: ${speech.recognition.source}; audio=${speech.recognition.audioDurationMs} ms")
            appendLine("Ready=${speech.recognition.readyLatencyMs} ms; final=${speech.recognition.finalLatencyMs} ms")
            for (hypothesis in speech.recognition.hypotheses) appendLine("RAW #${hypothesis.rank + 1}: ${hypothesis.text} / confidence=${hypothesis.acousticConfidence ?: "미제공"}")
            appendLine("Partial observations=${speech.recognition.partialResults.size}")
            appendLine("Bias=${speech.context.biasStrings().size} / mode=${speech.context.environment}")
            appendLine("Places: ${speech.context.entities.filter { it.kind == dev.localphone.core.SpeechEntityKind.PLACE }.joinToString { it.name }}")
            appendLine("Apps: ${speech.context.entities.filter { it.kind == dev.localphone.core.SpeechEntityKind.APP }.joinToString { it.name }}")
            appendLine("Tools: ${speech.context.toolPhrases.joinToString()}")
            appendLine("RESOLVED: ${speech.resolution.selectedText}; changed=${speech.resolution.correctionApplied}")
            appendLine("Evidence: ${speech.resolution.evidence.joinToString()}")
            appendLine("Clarification=${speech.resolution.requiresClarification}; resolver=${speech.resolverMs} ms")
            appendLine()
        }
        val text = speechText + if (trace == null) "아직 음성 호출 실행 기록이 없습니다." else buildString {
            fun ms(start: String, end: String): String {
                val a = trace.times[start]; val b = trace.times[end]
                return if (a == null || b == null) "미측정" else "${b - a} ms"
            }
            appendLine("Last Invocation: ${trace.sessionId}")
            appendLine("Transcript: ${trace.transcript}")
            appendLine("Input: ${trace.origin}")
            appendLine("App: ${trace.appVersion} (code ${trace.appVersionCode})")
            appendLine("Installation: ${trace.profile}")
            appendLine("AI enabled: ${trace.modelEnabled}")
            appendLine("Selected AI: ${trace.selectedModel}")
            appendLine("화면 추론 AI: ${trace.uiModel.ifBlank { "해당 없음" }}")
            appendLine("Clarification: ${trace.clarification}")
            appendLine("Mic ready: ${ms("T0", "T2")}")
            appendLine("STT after capture: ${ms("T4", "T5")}")
            appendLine("STT load: ${trace.stt?.modelLoadMs ?: "미측정"} ms / cold=${trace.stt?.coldModel}")
            appendLine("Model load: ${trace.agentLoad?.loadMs ?: "미측정"} ms / available=${trace.agentLoad?.available}")
            appendLine("Model inference: ${trace.modelInferenceMs ?: "미측정"} ms")
            appendLine("음성 인식 완료 → 작업 완료: ${ms("T5", "T8")} (목표 10,000 ms)")
            appendLine("Prefill/generation: ${trace.modelResponse?.timing ?: "미측정"}")
            appendLine("Interpretation: ${trace.interpretation}")
            appendLine("Policy: ${trace.policy}")
            appendLine("Execution: ${trace.execution}")
            appendLine("Total latency: ${trace.totalMs} ms")
            appendLine("Error: ${trace.result} ${trace.error}")
            appendLine("\n프로필: ${ProfileScope(this@InvocationDebugActivity).user}")
            appendLine("Samsung Secure Folder 여부는 Android 공개 API로 확정하지 않습니다.")
            appendLine("\n" + GsonBuilder().setPrettyPrinting().create().toJson(trace))
        }
        val current = "현재 설치: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) / ${ProfileScope(this).user}\n선택 모델: ${graph.planner.selected.key}\n\n"
        setContentView(ScrollView(this).apply {
            addView(TextView(this@InvocationDebugActivity).apply {
                this.text = current + text; textSize = 14f; setTextIsSelectable(true)
                val padding = (24 * resources.displayMetrics.density).toInt(); setPadding(padding, padding * 2, padding, padding)
            })
        })
    }
}
