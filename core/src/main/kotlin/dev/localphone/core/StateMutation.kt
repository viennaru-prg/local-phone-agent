package dev.localphone.core

/** Changes to actual check/selection state are completion checkpoints in every app. */
object StateMutation {
    fun changed(action:AgentAction,before:ScreenView,after:ScreenView)=evidence(action,before,after).isNotEmpty()
    fun evidence(action:AgentAction,before:ScreenView,after:ScreenView):String {
        val id=when(action) { is AgentAction.Click -> action.id;is AgentAction.LongClick -> action.id;else -> return "" }
        if(before.snapshot.packageName!=after.snapshot.packageName) return ""
        val old=before.element(id)?:return ""
        val raw=before.snapshot.nodes[old.node]
        val now=after.elements.filter { e -> e.kind==old.kind && e.label==old.label &&
            after.snapshot.nodes[e.node].viewId==raw.viewId && after.snapshot.nodes[e.node].className==raw.className }.singleOrNull()?:return ""
        val changes=mutableListOf<String>()
        if(old.checked!=now.checked) changes += "켜짐/꺼짐 ${old.checked} -> ${now.checked}"
        if(old.selected!=now.selected) changes += "선택됨 ${old.selected} -> ${now.selected}"
        return if(changes.isEmpty()) "" else "실제 관찰 상태 변경 '${old.label}': ${changes.joinToString(", ")}"
    }
}
