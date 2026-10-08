package dev.localphone.core

import com.google.gson.Gson
import com.google.gson.JsonParser

/** Strict model output, never a canned decision or a rule-based replacement. Runtime validates arguments again. */
object ModelToolOutput {
    fun decode(output: String): List<RawToolCall> {
        require(output.length in 1..16000) { "Model output empty/too large" }
        val trimmed = output.trim().let {
            if (it.startsWith("```json\n") && it.endsWith("```")) it.removePrefix("```json\n").removeSuffix("```").trim() else it
        }
        val root = JsonParser.parseString(trimmed)
        require(root.isJsonObject && root.asJsonObject.keySet() == setOf("calls")) { "Expected only calls" }
        val calls = root.asJsonObject.get("calls")
        require(calls.isJsonArray && calls.asJsonArray.size() <= ToolRegistry.MAX_STEPS) { "Invalid calls" }
        return calls.asJsonArray.map { value ->
            require(value.isJsonObject && value.asJsonObject.keySet() == setOf("name", "arguments"))
            val name = value.asJsonObject.get("name")
            val arguments = value.asJsonObject.get("arguments")
            require(name.isJsonPrimitive && name.asJsonPrimitive.isString && arguments.isJsonObject)
            @Suppress("UNCHECKED_CAST")
            RawToolCall(name.asString, Gson().fromJson(arguments, Map::class.java) as Map<String, Any?>)
        }
    }
}
