package dev.localphone.core

/** Bounded task memory of actual UI values, never the model's plans or expected outcomes. */
class ObservedFacts(private val limit:Int=12,private val goal:String="") {
    private val facts=linkedMapOf<String,String>()
    fun observe(view:ScreenView) {
        for(e in view.elements) {
            if(e.label.isBlank()) continue
            val raw=view.snapshot.nodes[e.node]
            val key=view.snapshot.packageName+":"+e.kind+":"+raw.viewId+":"+e.label.replace(Regex("[0-9]+(?:[.:][0-9]+)*"),"#")
            if(e.value.isBlank() && e.checked==null && !e.selected && !e.label.any(Char::isDigit) &&
                !GoalText.matches(e.label,GoalText.targetWords(goal)) && key !in facts) continue
            val value=buildString {
                append(view.snapshot.appLabel).append(": ").append(e.label)
                if(e.value.isNotBlank()) append(" 값=").append(e.value)
                e.checked?.let { append(if(it) " 켜짐" else " 꺼짐") }
                if(e.kind!=Kind.TEXT && e.kind!=Kind.INPUT) append(if(e.selected) " 선택됨" else " 미선택")
                if(!e.enabled) append(" 비활성")
            }.take(110)
            facts.remove(key);facts[key]=value
        }
        while(facts.size>limit) facts.remove(facts.keys.first())
    }
    fun lines()=facts.values.toList()
}
