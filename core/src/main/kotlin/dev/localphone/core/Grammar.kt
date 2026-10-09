package dev.localphone.core

/**
 * GBNF grammar for llama.cpp. It only allows the action JSON shape and element ids that exist on the
 * current screen, so a small model cannot produce prose, unknown actions or invented ids.
 */
object ActionGrammar {
    /** [withNote] adds a brief explanation after the action; disabling it produces fewer tokens. */
    fun forView(view: ScreenView, withNote: Boolean = true, excludedIds: Set<Int> = emptySet(),
                excludedLongIds:Set<Int> = excludedIds, excludedOps:Set<String> = emptySet()): String {
        val all = view.elements.filter { it.kind != Kind.TEXT && it.enabled && it.id !in excludedIds }.map { it.id }
        val longs = view.elements.filter { it.kind != Kind.TEXT && it.enabled && it.id !in excludedLongIds }.map { it.id }
        val inputs = view.inputs.filter { it.enabled }.map { it.id }
        val lists = view.lists.filter { it.enabled }.map { it.id }
        val actions = mutableListOf("open", "back", "wait", "media", "done", "ask", "fail", "scroll", "inspect")
        if (all.isNotEmpty()) actions += "click"
        if (longs.isNotEmpty()) actions += "longclick"
        if (inputs.isNotEmpty()) actions += "type"
        actions.removeAll(excludedOps)
        return buildString {
            // Choose the action from the live screen before generating its explanation. Otherwise
            // small models keep repeating the note they wrote before looking at the available actions.
            // The action is chosen first, so text after it cannot improve the choice; on a ~2B model each
            // extra Korean phrase costs seconds. The lean form keeps only `check` (used for verification).
            val tail = if (withNote) """",\"expect\":" short ",\"check\":" boolean ",\"note\":" short "}"""" else
                """",\"check\":" boolean "}""""
            appendLine("""root ::= "{\"action\":" (${actions.joinToString(" | ")}) $tail""")
            appendLine("""open ::= "\"open_app\",\"app\":" str""")
            appendLine("""back ::= "\"back\""""")
            appendLine("""wait ::= "\"wait\""""")
            appendLine("""media ::= "\"media\",\"key\":" ("\"play\"" | "\"pause\"" | "\"next\"" | "\"previous\"")""")
            appendLine("""done ::= "\"done\",\"say\":" str""")
            appendLine("""ask ::= "\"ask\",\"question\":" str""")
            appendLine("""fail ::= "\"fail\",\"reason\":" str""")
            val scrollId = if (lists.isNotEmpty()) """ ("," "\"id\":" sid)?""" else ""
            appendLine("""scroll ::= "\"scroll\",\"dir\":" ("\"down\"" | "\"up\"" | "\"left\"" | "\"right\"")$scrollId""")
            appendLine("""inspect ::= "\"inspect\",\"query\":" short""")
            if (all.isNotEmpty()) {
                appendLine("""click ::= "\"click\",\"id\":" id""")
                appendLine("id ::= " + all.joinToString(" | ") { "\"$it\"" })
            }
            if (longs.isNotEmpty()) {
                appendLine("""longclick ::= "\"long_click\",\"id\":" lid""")
                appendLine("lid ::= " + longs.joinToString(" | ") { "\"$it\"" })
            }
            if (inputs.isNotEmpty()) {
                appendLine("""type ::= "\"type\",\"id\":" iid ",\"text\":" str ",\"enter\":" ("true" | "false")""")
                appendLine("iid ::= " + inputs.joinToString(" | ") { "\"$it\"" })
            }
            if (lists.isNotEmpty()) appendLine("sid ::= " + lists.joinToString(" | ") { "\"$it\"" })
            appendLine("""short ::= "\"" char{0,60} "\""""")
            appendLine("""boolean ::= "true" | "false"""")
            appendLine("""str ::= "\"" char{0,120} "\""""")
            appendLine("""char ::= [^"\\\x00-\x1F] | "\\" ["\\/bfnrt]""")
        }
    }
}
