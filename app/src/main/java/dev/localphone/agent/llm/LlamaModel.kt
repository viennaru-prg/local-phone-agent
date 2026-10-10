package dev.localphone.agent.llm

import android.util.Log
import dev.localphone.agent.AgentApp
import dev.localphone.core.LanguageModel
import dev.localphone.core.ModelPrompt
import dev.localphone.core.Prompts
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal object LlamaNative {
    init { System.loadLibrary("agent_llama") }
    external fun load(path: String, threads: Int, nCtx: Int, gpuLayers: Int): Long
    external fun infer(handle: Long, prompt: ByteArray, maxTokens: Int, grammar: ByteArray, timeoutMs: Long, contextSlot:Int=0): ByteArray
    external fun metrics(handle: Long): LongArray
    external fun reset(handle: Long)
    external fun cancel(handle: Long)
    external fun unload(handle: Long)
}

data class InferenceStats(val promptTokens: Long, val reusedTokens: Long, val outputTokens: Long, val prefillMs: Long, val generationMs: Long)

/**
 * Keeps one GGUF model in memory for a while after use, so consecutive commands skip the multi-second
 * load. Loading starts as soon as the voice trigger opens (in parallel with speech recognition).
 */
class LlamaModel(private val app: AgentApp) : LanguageModel {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var handle = 0L
    private var loadedFile: File? = null
    private var idleJob: Job? = null
    @Volatile var lastStats: InferenceStats? = null; private set
    @Volatile var lastLoadMs = 0L; private set
    @Volatile var lastThought = ""; private set

    fun modelFile(): File? {
        val dir = app.modelsDir
        val chosen = app.prefs.modelFile.takeIf { it.isNotBlank() }?.let { File(dir, it) }?.takeIf { it.isFile }
        // Default: Kakao Kanana 1.5 2.1B (most accurate small model on the S25 eval), then any Q4_K_M build.
        return chosen ?: dir.listFiles { f -> f.name.endsWith(".gguf") }
            ?.sortedWith(compareByDescending<File> { it.name.contains("kanana", true) }
                .thenByDescending { it.name.contains("Q4_K_M") }.thenBy { it.name })?.firstOrNull()
    }
    val loaded get() = handle != 0L

    /**
     * Loads the model while the user is still speaking (memory only, no GPU decoding): most commands
     * never need it, and decoding for nothing only heated the phone.
     */
    fun preload() { scope.launch { runCatching { mutex.withLock { ensureLoaded() } }.onFailure { Log.w(TAG, "preload", it) } } }

    /** Loads the model and decodes the step system prompt, once a command is known to need the screen agent. */
    fun prewarm() { scope.launch { runCatching { mutex.withLock { ensureLoaded(); warmPrefix() } }.onFailure { Log.w(TAG, "prewarm", it) } } }

    /** The step system prompt is the long shared prefix of every step; cold, it costs ~14 s of prefill. */
    private suspend fun warmPrefix() {
        if (straggler != null) return
        val text = frame(ModelPrompt(Prompts.system(app.prefs.withNote), ""), loadedFile!!.name)
        val t0 = System.currentTimeMillis()
        LlamaNative.reset(handle)
        withContext(Dispatchers.IO) { LlamaNative.infer(handle, text.toByteArray(), 0, ByteArray(0), STEP_TIMEOUT_MS, 0) }
        val m = LlamaNative.metrics(handle)
        Log.i(TAG, "warm prefix tokens=${m[0]} reused=${m[1]} in ${System.currentTimeMillis() - t0}ms")
    }

    private suspend fun ensureLoaded() {
        idleJob?.cancel()
        val file = modelFile() ?: throw IllegalStateException("모델 파일이 없습니다. 설정에서 모델 위치를 확인해 주세요.")
        if (handle != 0L && loadedFile == file) return
        if (handle != 0L) { LlamaNative.unload(handle); handle = 0L }
        val t0 = System.currentTimeMillis()
        // Compiled Adreno kernels are cached so only the very first GPU load pays the compile time.
        android.system.Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", File(app.noBackupFilesDir, "cl-cache").apply { mkdirs() }.absolutePath, true)
        handle = withContext(Dispatchers.IO) { LlamaNative.load(file.absolutePath, app.prefs.threads, 3072, if (app.prefs.gpu) 99 else 0) }
        loadedFile = file
        lastLoadMs = System.currentTimeMillis() - t0
        Log.i(TAG, "loaded ${file.name} in ${lastLoadMs}ms")
    }

    /**
     * A native call that overruns (e.g. the phone is swapping) is abandoned after [STEP_TIMEOUT_MS] so
     * the command fails with a clear message instead of hanging. The stuck call keeps the native
     * handle until it returns; the next call waits for it first.
     */
    private var straggler: Deferred<ByteArray>? = null

    override suspend fun decide(prompt: ModelPrompt, grammar: String): String = mutex.withLock {
        // A call abandoned after its timeout still owns the native context; wait for it, but not forever.
        straggler?.let { s ->
            withTimeoutOrNull(20_000) { runCatching { s.await() } } ?: throw IllegalStateException("이전 AI 계산이 끝나지 않았어요. 잠시 후 다시 시도해 주세요.")
            straggler = null
        }
        ensureLoaded()
        val text = frame(prompt, loadedFile!!.name)
        val h = handle
        LlamaNative.reset(h)
        val maxTokens = if (think) 200 + app.prefs.thinkChars else 200
        // Slot 0 keeps the step prompt's long system prefix warm; every side question (verify, place
        // choice) goes to slot 1 so it cannot evict that prefix (a cold step prefill takes ~14 s).
        val contextSlot = if (prompt.system == Prompts.SYSTEM || prompt.system == Prompts.SYSTEM_NO_NOTE) 0 else 1
        val bytes = text.toByteArray()
        val grammarBytes = thinkingGrammar(grammar).toByteArray()
        try {
            // A native timeout keeps the decoded part of the prompt in the slot, so one more attempt only
            // continues where the first stopped (a cold, crowded screen can need a little over 30 s).
            for (attempt in 1..2) {
                val job = scope.async(Dispatchers.IO) { LlamaNative.infer(h, bytes, maxTokens, grammarBytes, STEP_TIMEOUT_MS, contextSlot) }
                val out = try {
                    withTimeoutOrNull(STEP_TIMEOUT_MS + 5_000) { job.await() }
                } catch (e: CancellationException) {
                    LlamaNative.cancel(h); straggler = job; throw e
                } catch (e: IllegalStateException) {
                    if (e.message != "inference timeout") throw e
                    Log.w(TAG, "inference timeout, attempt $attempt")
                    if (attempt == 2) throw IllegalStateException(TOO_SLOW)
                    LlamaNative.reset(h)
                    continue
                }
                if (out == null) {
                    LlamaNative.cancel(h); straggler = job
                    throw IllegalStateException(TOO_SLOW)
                }
                return@withLock String(out).also { lastThought = it.substringBefore("</think>", "").removePrefix("<think>").trim() }.let(::stripThink)
            }
            throw IllegalStateException(TOO_SLOW)
        } finally {
            val m = LlamaNative.metrics(h)
            lastStats = InferenceStats(m[0], m[1], m[2], m[3], m[4])
            scheduleIdleUnload()
        }
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(app.prefs.keepLoadedMinutes * 60_000L)
            unload()
        }
    }

    suspend fun unload() = mutex.withLock {
        straggler?.let { runCatching { it.await() }; straggler = null }
        if (handle != 0L) { LlamaNative.unload(handle); handle = 0L; loadedFile = null }
    }

    /**
     * ChatML. With [think] off, Qwen3 hybrid models get an empty think block so they answer directly.
     * With it on, the grammar allows a short bounded reasoning block before the JSON.
     */
    private fun frame(p: ModelPrompt, fileName: String): String {
        // Only this adapter may emit control tokens; screen text is neutralized.
        fun clean(s: String) = s.replace("<|", "< |").replace("[|", "[ |")
        val name = fileName.lowercase()
        return when {
            // LG EXAONE 4.0: [|role|] markers, non-reasoning mode via an empty think block.
            "exaone" in name -> "[|system|]\n${clean(p.system)}[|endofturn|]\n[|user|]\n${clean(p.user)}[|endofturn|]\n[|assistant|]\n" +
                if (think) "" else "<think>\n\n</think>\n\n"
            // Kakao Kanana 1.5 (Llama 3.1 format).
            "kanana" in name || "llama" in name -> "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n${clean(p.system)}<|eot_id|>" +
                "<|start_header_id|>user<|end_header_id|>\n\n${clean(p.user)}<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
            // Qwen (ChatML). Hybrid-thinking models get an empty think block so they answer directly.
            else -> {
                val noThink = if (think || "instruct" in name) "" else "<think>\n\n</think>\n\n"
                "<|im_start|>system\n${clean(p.system)}<|im_end|>\n<|im_start|>user\n${clean(p.user)}<|im_end|>\n<|im_start|>assistant\n$noThink"
            }
        }
    }

    private val think get() = app.prefs.think

    /** Wraps the action grammar with an optional bounded <think> block; strips it from the answer. */
    private fun thinkingGrammar(grammar: String): String {
        if (!think || grammar.isEmpty()) return grammar
        return grammar.replaceFirst("root ::=", "answer ::=") +
            "\nroot ::= \"<think>\\n\" thought \"</think>\\n\\n\" answer\nthought ::= [^<]{0,${app.prefs.thinkChars}}\n"
    }
    private fun stripThink(out: String) = out.substringAfter("</think>").trim()

    companion object {
        private const val TAG = "AgentLlm"
        const val STEP_TIMEOUT_MS = 30_000L
        private const val TOO_SLOW = "AI 응답이 너무 늦어 중단했어요. 휴대폰 메모리가 부족할 수 있어요."
    }
}
