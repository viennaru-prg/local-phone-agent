package dev.localphone.agent.runtime

import com.google.gson.Gson

/** A format constraint from the SAME tool catalog, never a command classifier or stored answer. */
internal object ToolJsonGrammar {
    fun compile(tools: List<Map<String, Any>>): String {
        val rules = linkedMapOf<String, String>()
        fun literal(value: String) = Gson().toJson(value)
        fun key(value: String) = literal(Gson().toJson(value))
        var index = 0
        fun value(schema: Map<String, Any>): String {
            val name = "value${index++}"
            rules[name] = if (schema["enum"] is List<*>) (schema["enum"] as List<*>).joinToString(" | ") { key(it.toString()) }
                else when (schema["type"]) {
                    "string" -> "string"
                    "integer" -> "integer"
                    "array" -> {
                        @Suppress("UNCHECKED_CAST") val item = value(schema["items"] as Map<String, Any>)
                        "\"[\" ws ($item (\",\" ws $item){0,7})? ws \"]\""
                    }
                    else -> error("Unsupported tool parameter type")
                }
            return name
        }
        tools.forEachIndexed { i, tool ->
            @Suppress("UNCHECKED_CAST")
            val parameters = tool["parameters"] as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val properties = parameters["properties"] as Map<String, Map<String, Any>>
            val fields = properties.entries.joinToString(" ws \",\" ws ") { (name, schema) ->
                key(name) + " ws \":\" ws " + value(schema)
            }
            rules["arguments$i"] = "\"{\" ws " + fields + " ws \"}\""
            rules["call$i"] = "\"{\" ws ${key("name")} ws \":\" ws ${key(tool["name"].toString())} ws \",\" ws " +
                "${key("arguments")} ws \":\" ws arguments$i ws \"}\""
        }
        rules["call"] = tools.indices.joinToString(" | ") { "call$it" }
        rules["root"] = "\"{\" ws ${key("calls")} ws \":\" ws \"[\" ws (call (ws \",\" ws call){0,5})? ws \"]\" ws \"}\" ws"
        rules["ws"] = "[ \\t\\n\\r]{0,4}"
        rules["integer"] = "\"-\"? ([0-9] | [1-9] [0-9]{0,8})"
        rules["string"] = """
            "\"" ([^"\\\x00-\x1f] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4}))* "\""
        """.trimIndent()
        return rules.entries.joinToString("\n") { (name, rule) -> "$name ::= $rule" } + "\n"
    }
}
