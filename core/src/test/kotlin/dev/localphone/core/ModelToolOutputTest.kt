package dev.localphone.core
import kotlinx.coroutines.runBlocking

import kotlin.test.*

class ModelToolOutputTest {
    @Test fun incompleteCompoundModelPlanCannotExecuteOnlyOnePartOfTheGoal() {
        val goal = "집으로 가면서 음악 틀어줘"
        for (partial in listOf(ToolPlan(listOf(Action.Navigate("집"))), ToolPlan(listOf(Action.MediaResume)))) {
            val checked = PlanGrounding.validate(partial, goal)
            assertTrue(checked.actions.isEmpty())
            assertNotNull(checked.unsupportedReason)
        }
        val complete = ToolPlan(listOf(Action.Navigate("집"), Action.MediaResume))
        assertEquals(complete, PlanGrounding.validate(complete, goal))
        val screenGoal = ToolPlan(listOf(Action.AppTask("", goal)))
        assertEquals(screenGoal, PlanGrounding.validate(screenGoal, goal))
    }
    @Test fun mapLaunchCannotBecomeNavigationToAnAppName() {
        val plan = PlanGrounding.validate(ToolPlan(listOf(Action.Navigate("네이버지도"))), "네이버지도 켜줘")
        assertTrue(plan.actions.isEmpty())
    }
    @Test fun simultaneousCommandIsNotAConditional() = runBlocking<Unit> {
        assertNull(CommandSafety.blockedReason("집으로 가면서 음악 틀어줘"))
        assertEquals(listOf(Action.Navigate("집으로"), Action.MediaResume), BasicCommandPlanner().plan("집으로 가면서 음악 틀어줘").actions)
        assertNotNull(CommandSafety.blockedReason("집에 가면 음악 틀어줘"))
    }
    @Test fun koreanCompoundCallsUseTheExistingRegistryAndGrounding() {
        val calls = ModelToolOutput.decode("""{"calls":[{"name":"navigate","arguments":{"destination":"집"}},{"name":"media_resume","arguments":{}}]}""")
        val plan = PlanGrounding.validate(ToolPlanDecoder.decode(calls), "집으로 가자 음악 틀어줘")
        assertEquals(listOf(Action.Navigate("집"), Action.MediaResume), plan.actions)
    }
    @Test fun hallucinatedFunctionsParametersAndDestinationsNeverReachRuntime() {
        for (output in listOf(
            """{"calls":[{"name":"delete_everything","arguments":{}}]}""",
            """{"calls":[{"name":"navigate","arguments":{"destination":"집","latitude":37.5}}]}""",
            """{"calls":[{"name":"navigate","arguments":{"destination":"미지의 장소"}}]}""")) {
            assertTrue(PlanGrounding.validate(ToolPlanDecoder.decode(ModelToolOutput.decode(output)), "집으로 가자").actions.isEmpty())
        }
    }
    @Test fun generatedProseExtraFieldsOrNonObjectArgumentsAreRejected() {
        for (output in listOf("completed", "{}", "{\"calls\":[],\"completed\":true}",
            "{\"calls\":[{\"name\":\"open_app\",\"arguments\":\"지도\"}]}",
            "{\"calls\":[{\"name\":17,\"arguments\":{}}]}", "{\"calls\":[]} trailing prose")) {
            assertFails { ModelToolOutput.decode(output) }
        }
    }
    @Test fun emptyCallsCannotPretendAnActionCompleted() {
        assertTrue(ToolPlanDecoder.decode(ModelToolOutput.decode("{\"calls\":[]}")).actions.isEmpty())
    }
    @Test fun phoneticAliasNormalizationNeedsUniqueSoundEvidenceAndNavigationIntent() {
        val home = ToolPlan(listOf(Action.Navigate("집")))
        assertEquals(home, PlanGrounding.validate(home, "지브로 가자"))
        assertTrue(PlanGrounding.validate(home, "김포로 가자").actions.isEmpty())
        assertTrue(PlanGrounding.validate(home, "지브로 사진 보여줘").actions.isEmpty())
    }
}
