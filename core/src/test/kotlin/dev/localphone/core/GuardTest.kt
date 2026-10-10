package dev.localphone.core

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GuardTest {
    private fun view(goal: String, vararg elements: List<String>) = Evaluator.view(EvalCase("t", goal, "앱", elements.toList(), emptyList()))

    @Test fun phoneNumbersAndCallButtonsNeedACallRequest() {
        val goal = "이담한정식까지 얼마나 걸려"
        val v = view(goal, listOf("항목", "0507-1462-2221 전화번호 0507-1462-2221"), listOf("버튼", "전화"), listOf("항목", "휴대전화 정보"))
        assertNotNull(Guard.blocked(goal, AgentAction.Click(1), v))
        assertNotNull(Guard.blocked(goal, AgentAction.Click(2), v))
        // A menu that only mentions a phone is not a call.
        assertNull(Guard.blocked(goal, AgentAction.Click(3), v))
        assertNull(Guard.blocked("이담한정식에 전화해줘", AgentAction.Click(1), v))
    }
}
