package dev.localphone.agent.runtime

import java.io.File
import dev.localphone.core.RawToolCall

enum class LocalModelId(val key: String) {
    FUNCTIONGEMMA("functiongemma"), QWEN3("qwen3");
    companion object { fun from(key: String) = entries.firstOrNull { it.key == key } ?: FUNCTIONGEMMA }
}
enum class ModelRuntimeState { INSTALLED, LOADING, READY, CURRENT, FAILED }
data class EmbeddedModelInfo(val id: String, val name: String, val asset: String, val bytes: Long,
    val sha256: String, val repository: String, val revision: String, val quantization: String,
    val runtime: String, val contextTokens: Int, val license: String)
data class ModelInferenceTiming(val promptTokens: Long, val reusedInputTokens: Long, val outputTokens: Long,
    val prefillMs: Long, val generationMs: Long)
data class ModelResponse(val calls: List<RawToolCall>, val raw: String, val inferenceMs: Long, val formatError: String? = null,
    val timing: ModelInferenceTiming? = null)
data class ModelPreparation(val available: Boolean, val cold: Boolean, val loadMs: Long, val error: String? = null,
    val modelId: String = "", val backend: String = "CPU")
data class ModelLoadTimings(val modelId: String, val extractionMs: Long, val nativeLoadMs: Long, val probeMs: Long, val inputPrefixRestored: Boolean = false)

/** A runtime adapter cannot execute Android tools. The common policy/runtime owns every effect. */
interface LocalAgentModel {
    val info: EmbeddedModelInfo
    val loaded: Boolean
    suspend fun load(weight: File)
    suspend fun infer(system: String, user: String, tools: List<Map<String, Any>>, maxOutputTokens: Int = 384): ModelResponse
    fun cancel()
    suspend fun unload()
}
