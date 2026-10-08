package dev.localphone.agent.runtime

import android.os.SystemClock
import com.google.gson.Gson
import dev.localphone.core.ModelToolOutput
import kotlinx.coroutines.*
import java.io.File

internal object LlamaNative {
    init { System.loadLibrary("localphone_llama") }
    external fun load(path: String, threads: Int): Long
    external fun begin(handle: Long)
    external fun infer(handle: Long, prompt: ByteArray, limit: Int, grammar: ByteArray, timeoutMs: Long, snapshotPath: ByteArray = byteArrayOf()): ByteArray
    external fun restoreInput(handle: Long, path: ByteArray, prompt: ByteArray): Boolean
    external fun metrics(handle: Long): LongArray
    external fun cancel(handle: Long)
    external fun unload(handle: Long)
}

class QwenPlanner(override val info: EmbeddedModelInfo, cache: File? = null) : LocalAgentModel {
    private val lifecycle = Any()
    private var handle = 0L
    private val inputCache = cache?.let { QwenInputCache(it, info) }
    internal var prefixRestored = false
        private set
    private var phoneInputReady = false
    private fun phoneProbePrompt() = frame(ModelToolCatalog.SYSTEM, "Open Wi-Fi settings.", ModelToolCatalog.phone).toByteArray()
    override val loaded get() = synchronized(lifecycle) { handle != 0L }
    override suspend fun load(weight: File) = withContext(Dispatchers.IO) {
        check(!loaded)
        val pointer = LlamaNative.load(weight.absolutePath, 4)
        check(pointer != 0L)
        synchronized(lifecycle) { handle = pointer }
        prefixRestored = inputCache?.restore(pointer, phoneProbePrompt()) == true
        phoneInputReady = prefixRestored
    }
    private fun frame(system: String, user: String, tools: List<Map<String, Any>>): String {
        // Only adapter-owned framing can introduce ChatML control tokens.
        fun data(value: String) = value.replace("<|", "< |")
        // Keep the full catalog and its semantics, without repeating JSON-schema boilerplate in
        // every prefill. The grammar and shared decoder still enforce the original schemas.
        val ui = tools.any { it["name"].toString().startsWith("ui_") }
        // The UI prompt's catalog stays constant between screens. Per-observation node
        // constraints remain in the grammar; putting them in the system prefix forces a
        // full prefill each time a button disappears or changes its ID.
        val promptTools = if (ui) ModelToolCatalog.ui else tools
        val functions = promptTools.joinToString("\n") { tool ->
            @Suppress("UNCHECKED_CAST") val schema = tool["parameters"] as Map<String, Any>
            @Suppress("UNCHECKED_CAST") val properties = schema["properties"] as Map<String, Map<String, Any>>
            val args = properties.entries.joinToString(",") { (name, value) ->
                "$name:${value["type"]}" + if (value["enum"] is List<*>) "=${Gson().toJson(value["enum"])}" else ""
            }
            "${tool["name"]}($args): ${tool["description"]}"
        }
        val taskHint = if (ui) "Exactly one UI call. A new goal-related completion label uses ui_complete; never click a non-actionable text label."
            else "0-6 calls. Other app goals use perform_app_task with goal equal to the whole user command, copied verbatim. Preserve Korean names."
        val instruction = "$system\nFunctions:\n$functions\nReturn JSON {\"calls\":[{\"name\":\"function name\",\"arguments\":{}}]}. No prose. $taskHint /no_think"
        // Official Qwen non-thinking suffix; no generated thinking tokens are discarded or executed.
        return "<|im_start|>system\n${data(instruction)}<|im_end|>\n<|im_start|>user\n${data(user)}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
    }
    internal suspend fun phoneHealthProbe(): ModelResponse = inferInternal(ModelToolCatalog.SYSTEM, "Open Wi-Fi settings.", ModelToolCatalog.phone, 96,
        withContext(Dispatchers.IO) { inputCache?.begin() ?: byteArrayOf() })
    internal suspend fun commitPhonePrefix() = withContext(Dispatchers.IO) { runCatching { inputCache?.commit(phoneProbePrompt()) }; Unit }
    override suspend fun infer(system: String, user: String, tools: List<Map<String, Any>>, maxOutputTokens: Int): ModelResponse =
        inferInternal(system, user, tools, maxOutputTokens, byteArrayOf())
    private suspend fun inferInternal(system: String, user: String, tools: List<Map<String, Any>>, maxOutputTokens: Int, snapshot: ByteArray): ModelResponse = coroutineScope {
        val prompt = frame(system, user, tools)
        val ui = tools.any { it["name"].toString().startsWith("ui_") }
        val pointer = synchronized(lifecycle) { handle }
        check(pointer != 0L) { "Qwen 모델이 준비되지 않았습니다." }
        val started = SystemClock.elapsedRealtime()
        val phone = !ui && system == ModelToolCatalog.SYSTEM && tools == ModelToolCatalog.phone
        // UI planning replaces the in-memory prefix. Restore only the constant catalog
        // before a later phone command, then freshly decode that new user's tokens.
        if (phone && !phoneInputReady && snapshot.isEmpty())
            withContext(Dispatchers.IO) { inputCache?.restore(pointer, phoneProbePrompt()) }
        val grammar = ToolJsonGrammar.compile(tools).toByteArray(Charsets.UTF_8)
        ensureActive()
        LlamaNative.begin(pointer) // Reset BEFORE scheduling; a late worker must not erase cancellation.
        ensureActive()
        val timeoutMs = if (ui) 30_000L else 45_000L
        val worker = async(Dispatchers.IO) {
            ensureActive()
            LlamaNative.infer(pointer, prompt.toByteArray(Charsets.UTF_8), maxOutputTokens, grammar, timeoutMs, snapshot).toString(Charsets.UTF_8)
        }
        val raw = try { worker.await() } catch (cancelled: CancellationException) {
            phoneInputReady = false; cancel(); withContext(NonCancellable) { worker.join() }; throw cancelled
        } catch (failure: Exception) { phoneInputReady = false; throw failure }
        phoneInputReady = phone
        val decoded = runCatching { ModelToolOutput.decode(raw) }
        val metrics = LlamaNative.metrics(pointer)
        ModelResponse(decoded.getOrDefault(emptyList()), raw, SystemClock.elapsedRealtime() - started,
            decoded.exceptionOrNull()?.let { "모델 응답의 도구 JSON 형식이 올바르지 않습니다." },
            ModelInferenceTiming(metrics[0], metrics[1], metrics[2], metrics[3], metrics[4]))
    }
    override fun cancel() = synchronized(lifecycle) { if (handle != 0L) LlamaNative.cancel(handle) }
    override suspend fun unload() = withContext(Dispatchers.IO) {
        synchronized(lifecycle) { if (handle != 0L) { LlamaNative.unload(handle); handle = 0L } }
    }
}
