package dev.localphone.core

/** A visible affordance is not a result. Require observable result data before accepting model DONE. */
object CompletionGrounding {
    private val question=Regex("[?？]|할까요|하시겠|겠습니까|하시겠습니까")
    private val information=Regex("(?:알려|설명해|비교해|어때|얼마)(?:\\s*줘|\\s*주세요)?\\s*[.!?]?$" )
    private val stopRequest=Regex("꺼|종료|끝내|중지|중단|그만|멈")
    private val stopControl=Regex("종료|끝내|중지|중단|닫기|멈|stop|exit|close",RegexOption.IGNORE_CASE)
    private val choose=Regex("바꿔|변경|선택(?:해|하)|설정(?:해|하)|맞춰|골라|고르")
    /** A common postcondition, never an app-specific action plan. The AI must choose the control. */
    fun completedTermination(goal:String,action:AgentAction,before:ScreenView,after:ScreenView):Boolean {
        if(GoalScope.multiple(goal)) return false
        val request=ShortcutGoals.body(goal)
        if(Regex("그리고|유지|말고|대신|조건|않|나중|예약|[0-9]|(?:하면|지나면|때|후에|뒤에)").containsMatchIn(request)) return false
        if(!Regex("^.+?(?:을|를)?\\s*(?:꺼|종료|끝내|중단|중지|멈춰)(?:\\s*해|\\s*하)?(?:\\s*줘|\\s*주세요)?\\s*[.!?]?$").matches(request)) return false
        val clicked=(action as? AgentAction.Click)?.let { before.element(it.id) }?:return false
        // Closing a menu, switching a tab or going back is not a termination operation.
        if(!Regex("종료|끝내|중단|중지|\\bstop\\b|\\bexit\\b",RegexOption.IGNORE_CASE).containsMatchIn(clicked.label)) return false
        if(!clicked.enabled || before.signature==after.signature || before.snapshot.packageName!=after.snapshot.packageName) return false
        if(after.elements.any { it.label==clicked.label }) return false
        val activityData=before.elements.filter { it.kind==Kind.TEXT &&
            (it.label.any(Char::isDigit) || Regex("진행\\s*중|실행\\s*중|작동\\s*중|재생\\s*중|남은|\\brunning\\b|\\bactive\\b",RegexOption.IGNORE_CASE).containsMatchIn(it.label)) }
        if(activityData.any { old -> after.elements.any { it.kind==Kind.TEXT && it.label==old.label } }) return false
        val observed=after.snapshot.nodes.filter { !it.bounds.empty && it.bounds.right>0 && it.bounds.bottom>0 &&
            it.bounds.left<after.snapshot.width && it.bounds.top<after.snapshot.height }
        if(observed.any { it.ownLabel==clicked.label || question.containsMatchIn(it.ownLabel) }) return false
        return true
    }
    /** A requested visible value cannot be applied while its sibling remains selected. */
    fun conflictingChoice(goal:String,view:ScreenView):String? {
        if(!choose.containsMatchIn(goal)) return null
        val normalized=GoalText.normalize(goal)
        for(option in view.elements.filter { !it.selected && it.enabled && it.kind!=Kind.TEXT && it.checked==null }) {
            val label=GoalText.normalize(option.label)
            if(label.length !in 2..30 || !normalized.contains(label)) continue
            val raw=view.snapshot.nodes[option.node]
            val selected=view.elements.firstOrNull { other ->
                other.selected && other.kind==option.kind &&
                    view.snapshot.nodes[other.node].parent==raw.parent &&
                    view.snapshot.nodes[other.node].className==raw.className
            }?:continue
            return "요청한 '${option.label}'는 미선택이며 현재 실제 선택은 '${selected.label}'임"
        }
        return null
    }
    fun hasOutcome(goal:String,view:ScreenView,history:List<HistoryLine>):Boolean {
        if(conflictingChoice(goal,view)!=null) return false
        // An observed title is not completion of the requested verb while its execute control
        // is still available and no actual execution of that control has been observed.
        if(!information.containsMatchIn(goal)) {
            val request=GoalText.normalize(goal)
            val pending=view.elements.any { e ->
                val label=GoalText.normalize(e.label)
                e.enabled && !e.selected && e.checked==null && e.kind in setOf(Kind.BUTTON,Kind.ITEM) &&
                    label.isNotEmpty() && Commit.isCommit(e.label) &&
                    Regex(Regex.escape(label)+"(?:해줘|해주세요|해|줘|주세요|하기)[.!?]?$" ).containsMatchIn(request) &&
                    history.none { h -> h.action=="click \"${e.label}\"" && h.outcome.contains("바뀜") }
            }
            if(pending) return false
        }
        val words=GoalText.targetWords(goal)
        if(view.elements.any { e ->
            e.enabled && (e.checked!=null || e.selected ||
                (e.kind==Kind.INPUT && e.value.isNotBlank() && ShortcutGoals.literalSearch(goal)!=null) ||
                (e.kind==Kind.TEXT && !question.containsMatchIn(e.label) &&
                    (GoalText.matches(e.label,words) || (information.containsMatchIn(goal) && e.label.any(Char::isDigit)))))
        }) return true
        // Ending a running activity can return to an ordinary screen with no success toast.
        // Require the actual target to have been clicked and disappear, then still ask the model
        // whether this transition satisfies the *entire* goal (not a pending confirmation dialog).
        if(!stopRequest.containsMatchIn(goal)) return false
        val ended=history.asReversed().firstNotNullOfOrNull { h ->
            Regex("^click \"(.*)\"$").matchEntire(h.action)?.groupValues?.get(1)
                ?.takeIf { stopControl.containsMatchIn(it) && h.outcome.contains("바뀜") }
        }?:return false
        return view.elements.none { it.label==ended || question.containsMatchIn(it.label) }
    }
}
