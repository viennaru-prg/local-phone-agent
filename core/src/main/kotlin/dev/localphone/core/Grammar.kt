package dev.localphone.core

/**
 * GBNF grammar for llama.cpp. It only allows the action JSON shape and element ids that exist on the
 * current screen, so a small model cannot produce prose, unknown actions or invented ids.
 */
object ActionGrammar {
    /** [withNote] = the model writes a short reasoning note first; without it the answer is about half as long. */
    fun forView(view: ScreenView, withNote: Boolean = true): String {
        val all = view.elements.map { it.id }
        val inputs = view.inputs.map { it.id }
        val lists = view.lists.map { it.id }
        val actions = mutableListOf("open", "back", "wait", "media", "done", "ask", "fail", "scroll")
        if (all.isNotEmpty()) actions += listOf("click", "longclick")
        if (inputs.isNotEmpty()) actions += "type"
        return buildString {
            val head = if (withNote) """"{\"note\":" short ",\"action\":"""" else """"{\"action\":""""
            appendLine("""root ::= $head (${actions.joinToString(" | ")}) "}"""")
            appendLine("""open ::= "\"open_app\",\"app\":" str""")
            appendLine("""back ::= "\"back\""""")
            appendLine("""wait ::= "\"wait\""""")
            appendLine("""media ::= "\"media\",\"key\":" ("\"play\"" | "\"pause\"" | "\"next\"" | "\"previous\"")""")
            appendLine("""done ::= "\"done\",\"say\":" str""")
            appendLine("""ask ::= "\"ask\",\"question\":" str""")
            appendLine("""fail ::= "\"fail\",\"reason\":" str""")
            val scrollId = if (lists.isNotEmpty()) """ ("," "\"id\":" sid)?""" else ""
            appendLine("""scroll ::= "\"scroll\",\"dir\":" ("\"down\"" | "\"up\"")$scrollId""")
            if (all.isNotEmpty()) {
                appendLine("""click ::= "\"click\",\"id\":" id""")
                appendLine("""longclick ::= "\"long_click\",\"id\":" id""")
                appendLine("id ::= " + all.joinToString(" | ") { "\"$it\"" })
            }
            if (inputs.isNotEmpty()) {
                appendLine("""type ::= "\"type\",\"id\":" iid ",\"text\":" str ",\"enter\":" ("true" | "false")""")
                appendLine("iid ::= " + inputs.joinToString(" | ") { "\"$it\"" })
            }
            if (lists.isNotEmpty()) appendLine("sid ::= " + lists.joinToString(" | ") { "\"$it\"" })
            appendLine("""short ::= "\"" char{0,60} "\""""")
            appendLine("""str ::= "\"" char{0,120} "\""""")
            appendLine("""char ::= [^"\\\x00-\x1F] | "\\" ["\\/bfnrt]""")
        }
    }
}
