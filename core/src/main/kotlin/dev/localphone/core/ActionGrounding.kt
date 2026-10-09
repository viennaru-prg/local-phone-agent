package dev.localphone.core

/** Bind the decision to the same live control, even if unrelated counters or rows changed. */
object ActionGrounding {
    fun rebind(action: AgentAction, before: ScreenView, live: ScreenView): AgentAction? {
        if (before.snapshot.packageName != live.snapshot.packageName) return null
        val id = when (action) {
            is AgentAction.Click -> action.id
            is AgentAction.LongClick -> action.id
            is AgentAction.Type -> action.id
            is AgentAction.Scroll -> action.id
            else -> null
        }
        if (id == null) return action
        val expected = before.element(id) ?: return null
        val raw = before.snapshot.nodes[expected.node]
        val matches = live.elements.filter { candidate ->
            val node = live.snapshot.nodes[candidate.node]
            candidate.kind == expected.kind && candidate.label == expected.label &&
                candidate.enabled && candidate.selected == expected.selected && candidate.checked == expected.checked &&
                (action !is AgentAction.Type || candidate.value == expected.value) &&
                node.className == raw.className && node.viewId == raw.viewId && node.ownLabel == raw.ownLabel &&
                (raw.viewId.isNotBlank() || node.path == raw.path)
        }
        // A driving map redraws lane and distance views all the time, shifting tree paths of controls
        // that did not change. A label that is unique on both screens still names the same control.
        val target = matches.singleOrNull() ?: live.elements.filter { candidate ->
            candidate.kind == expected.kind && candidate.label == expected.label && candidate.enabled &&
                live.snapshot.nodes[candidate.node].className == raw.className && expected.label.isNotBlank()
        }.singleOrNull()?.takeIf { action !is AgentAction.Type && before.elements.count { it.label == expected.label } == 1 } ?: return null
        return when (action) {
            is AgentAction.Click -> action.copy(id = target.id)
            is AgentAction.LongClick -> action.copy(id = target.id)
            is AgentAction.Type -> action.copy(id = target.id)
            is AgentAction.Scroll -> action.copy(id = target.id)
            else -> action
        }
    }
}
