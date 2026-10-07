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
        val text = if (trace == null) "아직 음성 호출 기록이 없습니다." else buildString {
            fun ms(start: String, end: String): String {
                val a = trace.times[start]; val b = trace.times[end]
                return if (a == null || b == null) "미측정" else "${b - a} ms"
            }
            appendLine("Last Invocation: ${trace.sessionId}")
            appendLine("Transcript: ${trace.transcript}")
            appendLine("Clarification: ${trace.clarification}")
            appendLine("Mic ready: ${ms("T0", "T2")}")
            appendLine("STT after capture: ${ms("T4", "T5")}")
            appendLine("STT load: ${trace.stt?.modelLoadMs ?: "미측정"} ms / cold=${trace.stt?.coldModel}")
            appendLine("Model load: ${trace.agentLoad?.loadMs ?: "미측정"} ms / available=${trace.agentLoad?.available}")
            appendLine("Model inference: ${trace.modelInferenceMs ?: "미측정"} ms")
            appendLine("Interpretation: ${trace.interpretation}")
            appendLine("Policy: ${trace.policy}")
            appendLine("Execution: ${trace.execution}")
            appendLine("Total latency: ${trace.totalMs} ms")
            appendLine("Error: ${trace.result} ${trace.error}")
            appendLine("\n프로필: ${ProfileScope(this@InvocationDebugActivity).user}")
            appendLine("Samsung Secure Folder 여부는 Android 공개 API로 확정하지 않습니다.")
            appendLine("\n" + GsonBuilder().setPrettyPrinting().create().toJson(trace))
        }
        setContentView(ScrollView(this).apply {
            addView(TextView(this@InvocationDebugActivity).apply {
                this.text = text; textSize = 14f; setTextIsSelectable(true)
                val padding = (24 * resources.displayMetrics.density).toInt(); setPadding(padding, padding * 2, padding, padding)
            })
        })
    }
}
