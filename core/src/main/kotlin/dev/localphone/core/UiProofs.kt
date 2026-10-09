package dev.localphone.core

import com.google.gson.JsonObject

/** Model success must cite observable values, not just a convincing explanation. */
object UiProofs {
    /** Values stay on the host: short references avoid regenerating (or inventing) evidence. */
    private fun references(goal:String,view:ScreenView,history:List<HistoryLine>):Map<String,String> = buildMap {
        for(e in view.elements.filter { it.enabled }) {
            if(e.checked!=null || e.selected || (e.kind==Kind.INPUT && e.value.isNotBlank()) ||
                ((e.kind==Kind.TEXT || informationGoal(goal)) && e.label.isNotBlank())) put("s${e.id}",e.label)
        }
        history.forEachIndexed { index,h ->
            if(index<history.size-16 || !actualChange(h)) return@forEachIndexed
            val clicked=Regex("^click \"(.*)\"$").matchEntire(h.action)?.groupValues?.get(1)
            val gone= !clicked.isNullOrBlank() && view.elements.none { it.label.contains(clicked) }
            put("h${index+1}",h.action + " → " + h.outcome +
                if(gone) "; 현재 관찰: '$clicked' 사라짐, 현재 화면에 없음" else "")
        }
    }

    fun historyCatalog(goal:String,view:ScreenView,history:List<HistoryLine>):String =
        references(goal,view,history).filterKeys { it.startsWith("h") }.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    /** Only observed data and changed execution steps are possible success references. */
    fun grammar(goal:String,view:ScreenView,history:List<HistoryLine>):String {
        val options=references(goal,view,history).keys.map { com.google.gson.JsonPrimitive("\"$it\"").toString() }
        val result=if(options.isEmpty()) "\"false,\\\"proofs\\\":[]\"" else
            "(\"false,\\\"proofs\\\":[]\" | \"true,\\\"proofs\\\":[\" proof (\",\" proof){0,3} \"]\")"
        return buildString {
            appendLine("""root ::= "{\"ok\":" $result ",\"reason\":\"" char{0,30} "\"}"""")
            if(options.isNotEmpty()) appendLine("proof ::= " + options.joinToString(" | "))
            appendLine("""char ::= [^"\\\x00-\x1F] | "\\" ["\\/bfnrt]""")
        }
    }

    fun failure(json:JsonObject,goal:String,view:ScreenView,history:List<HistoryLine>):String? {
        // Legacy codecs remain readable. The production grammar requires proofs on every success.
        if(!json.has("proofs")) return null
        return runCatching {
            val proofs=json.getAsJsonArray("proofs")
            if(proofs.size() !in 1..4) return "완료를 뒷받침할 관찰 증거가 없음"
            for(item in proofs) {
                if(item.isJsonPrimitive) {
                    val ref=item.asString
                    if(ref !in references(goal,view,history)) return "완료 근거 $ref 는 실제 관찰 또는 변경 이력에 없음"
                    continue
                }
                val proof=item.asJsonObject
                val id=proof.get("id").asInt
                val field=proof.get("field").asString
                val expected=proof.get("value").asString
                val normalized=GoalText.normalize(expected)
                if(normalized.isEmpty()) return "빈 관찰 증거"
                val element=view.element(id)
                val ok=when(field) {
                    "selected" -> element!=null && element.selected.toString()==expected
                    "checked" -> element?.checked!=null && element.checked.toString()==expected
                    "input", "value" -> element?.kind==Kind.INPUT && GoalText.normalize(element.value).contains(normalized)
                    "text" -> element!=null && (element.kind==Kind.TEXT || informationGoal(goal)) &&
                        GoalText.normalize(element.label).contains(normalized)
                    "history" -> id==0 && history.any { h -> actualChange(h) && GoalText.normalize(h.action).contains(normalized) }
                    "absent" -> id==0 && history.any { h -> actualChange(h) && h.action.startsWith("click ") &&
                        GoalText.normalize(h.action).contains(normalized) } &&
                        view.elements.none { GoalText.normalize(it.label).contains(normalized) }
                    else -> false
                }
                if(!ok) return "완료 증거 불일치: [$id] $field=$expected 는 실제 화면/실행 이력에서 확인되지 않음"
            }
            null
        }.getOrElse { "완료 증거 형식 오류" }
    }

    private fun actualChange(history:HistoryLine)=history.outcome.contains("바뀜") || history.outcome.startsWith("열림")
    private fun informationGoal(goal:String)=Regex("알려|설명|비교|차이|얼마|어때|이름|제목").containsMatchIn(goal)
}
