package dev.localphone.agent.runtime

import android.os.SystemClock
import com.google.gson.Gson
import dev.localphone.core.ModelToolOutput
import kotlinx.coroutines.*
import java.io.File

internal object LlamaNative {
    init { System.loadLibrary("localphone_llama") }
    external fun load(path: String, threads: Int): Long
    external fun infer(handle: Long, prompt: ByteArray, limit: Int, grammar: ByteArray): ByteArray
    external fun cancel(handle: Long)
    external fun unload(handle: Long)
}

class QwenPlanner(override val info: EmbeddedModelInfo) : LocalAgentModel {
    private val lifecycle = Any()
    private var handle = 0L
    override val loaded get() = synchronized(lifecycle) { handle != 0L }
    override suspend fun load(weight: File) = withContext(Dispatchers.IO) {
        check(!loaded)
        val pointer = LlamaNative.load(weight.absolutePath, 4)
        check(pointer != 0L)
        synchronized(lifecycle) { handle = pointer }
    }
    override suspend fun infer(system: String, user: String, tools: List<Map<String, Any>>, maxOutputTokens: Int): ModelResponse = coroutineScope {
        // Only adapter-owned framing can introduce ChatML control tokens.
        fun data(value: String) = value.replace("<|", "< |")
        val instruction = "$system\nFunctions: ${Gson().toJson(tools)}\nReturn only JSON: {\"calls\":[{\"name\":\"function name\",\"arguments\":{}}]}. Use 0-6 calls. No prose. Use perform_app_task for app goals lacking a dedicated function. Preserve Korean names and the original goal. /no_think"
        // Official Qwen non-thinking suffix; no generated thinking tokens are discarded or executed.
        val prompt = "<|im_start|>system\n${data(instruction)}<|im_end|>\n<|im_start|>user\n${data(user)}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        val pointer = synchronized(lifecycle) { handle }
        check(pointer != 0L) { "Qwen 모델이 준비되지 않았습니다." }
        val started = SystemClock.elapsedRealtime()
        val grammar = ToolJsonGrammar.compile(tools).toByteArray(Charsets.UTF_8)
        val worker = async(Dispatchers.IO) { LlamaNative.infer(pointer, prompt.toByteArray(Charsets.UTF_8), maxOutputTokens, grammar).toString(Charsets.UTF_8) }
        val raw = try { worker.await() } catch (cancelled: CancellationException) {
            cancel(); withContext(NonCancellable) { worker.join() }; throw cancelled
        }
        val decoded = runCatching { ModelToolOutput.decode(raw) }
        ModelResponse(decoded.getOrDefault(emptyList()), raw, SystemClock.elapsedRealtime() - started,
            decoded.exceptionOrNull()?.let { "모델 응답의 도구 JSON 형식이 올바르지 않습니다." })
    }
    override fun cancel() = synchronized(lifecycle) { if (handle != 0L) LlamaNative.cancel(handle) }
    override suspend fun unload() = withContext(Dispatchers.IO) {
        synchronized(lifecycle) { if (handle != 0L) { LlamaNative.unload(handle); handle = 0L } }
    }
}
