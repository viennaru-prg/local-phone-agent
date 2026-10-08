package dev.localphone.agent.runtime

import com.google.ai.edge.litertlm.*
import com.google.gson.Gson
import dev.localphone.core.RawToolCall
import dev.localphone.core.SettingsPage
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.*

/** Actual LiteRT-LM CPU runtime; no rule grammar or Android execution in this adapter. */
class FunctionGemmaPlanner(override val info: EmbeddedModelInfo, private val cache: File) : LocalAgentModel {
    private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    override val loaded get() = engine != null
    override suspend fun load(weight: File) = withContext(Dispatchers.IO) {
        check(!loaded)
        cache.mkdirs()
        cache.listFiles()?.filter { it.isFile && it.name.contains(".xnnpack_cache_") && !it.name.startsWith(weight.name + ".") }
            ?.forEach { it.delete() }
        val candidate = Engine(EngineConfig(weight.absolutePath, backend = Backend.CPU(numOfThreads = 4),
            maxNumTokens = info.contextTokens, cacheDir = cache.apply { mkdirs() }.absolutePath))
        try { candidate.initialize(); engine = candidate } catch (failure: Exception) { candidate.close(); throw failure }
    }
    override suspend fun infer(system: String, user: String, tools: List<Map<String, Any>>, maxOutputTokens: Int): ModelResponse = coroutineScope {
        val runtime = checkNotNull(engine) { "FunctionGemma 모델이 준비되지 않았습니다." }
        val started = SystemClock.elapsedRealtime()
        val worker = async(Dispatchers.IO) {
            runtime.createConversation(ConversationConfig(systemInstruction = Contents.of(
                "You are a model that can do function calling with the following functions. $system"),
                tools = nativeSchemas(tools).map { tool(SchemaTool(Gson().toJson(it))) }, automaticToolCalling = false,
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))).use { session ->
                conversation = session
                try {
                    val response = session.sendMessage(user)
                    val nativeCalls = response.toolCalls.map { RawToolCall(it.name, it.arguments) }
                    ModelResponse(nativeCalls.map(::canonicalCall), Gson().toJson(mapOf("text" to response.toString(), "native_calls" to nativeCalls)),
                        SystemClock.elapsedRealtime() - started)
                } finally { conversation = null }
            }
        }
        try { worker.await() } catch (cancelled: CancellationException) {
            cancel(); withContext(NonCancellable) { worker.join() }; throw cancelled
        }
    }
    override fun cancel() { runCatching { conversation?.cancelProcess() } }
    override suspend fun unload() = withContext(Dispatchers.IO) { check(conversation == null); engine?.close(); engine = null }
    // Same canonical catalog/capabilities. This adapter projects the two equivalent operations
    // to names used by the official Mobile Actions fine-tune, then maps actual generated calls back.
    // No user-command parser or fixed model answer is used here.
    private fun nativeSchemas(tools: List<Map<String, Any>>): List<Map<String, Any>> = tools.flatMap { schema ->
        when (schema["name"]) {
            "open_settings" -> SettingsPage.entries.map { page -> mapOf<String, Any>(
                "name" to "open_${page.key}_settings", "description" to "Opens ${page.displayName} settings for review.",
                "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())) }
            "navigate" -> listOf(schema + mapOf("name" to "show_location_on_map", "parameters" to mapOf(
                "type" to "object", "properties" to mapOf("location" to mapOf("type" to "string",
                    "description" to "Place named by the user for route guidance.")), "required" to listOf("location"))))
            else -> listOf(schema)
        }
    }
    private fun canonicalCall(call: RawToolCall): RawToolCall {
        if (call.name == "show_location_on_map" && call.arguments.keys == setOf("location"))
            return RawToolCall("navigate", mapOf("destination" to call.arguments["location"]))
        val page = SettingsPage.entries.singleOrNull { call.name == "open_${it.key}_settings" }
        if (page != null && call.arguments.isEmpty()) return RawToolCall("open_settings", mapOf("page" to page.key))
        return call
    }
    private class SchemaTool(private val schema: String) : OpenApiTool {
        override fun getToolDescriptionJsonString() = schema
        override fun execute(paramsJsonString: String): String = error("Automatic tool execution is disabled")
    }
}
