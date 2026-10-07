package dev.localphone.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VoiceSessionTest {
    @Test fun pauseNextAndResumeAreDifferentWholeCommands() = runTest {
        val planner = BasicCommandPlanner()
        for ((text, action) in listOf("노래 멈춰" to Action.MediaPause, "노래 꺼" to Action.MediaPause,
            "다음 곡" to Action.MediaNext, "다음 곡 틀어" to Action.MediaNext,
            "다음 노래 틀어" to Action.MediaNext, "노래 재생해줘" to Action.MediaResume)) {
            assertEquals(listOf(action), planner.plan(text).actions, text)
        }
    }
    @Test fun uncertainOrSaveCompoundDoesNotSkipOrResume() = runTest {
        for (text in listOf("다음 곡 틀어 라고 하면", "이 노래 저장하고 다음 곡 틀어", "노래 멈춰 말고 다음 곡", "다음 곡 틀까")) {
            assertTrue(BasicCommandPlanner().plan(text).actions.isEmpty(), text)
        }
    }
    @Test fun modelCannotConfusePauseWithResumeOrInventSkip() {
        for ((action, input) in listOf(Action.MediaResume to "노래 멈춰", Action.MediaPause to "노래 재생해줘", Action.MediaNext to "그 노래 추가해")) {
            assertNotNull(PlanGrounding.validate(ToolPlan(listOf(action)), input).unsupportedReason)
        }
        assertNull(PlanGrounding.validate(ToolPlan(listOf(Action.MediaNext)), "다음 곡 틀어").unsupportedReason)
    }
    @Test fun mediaCapabilitiesAreCheckedBeforeNavigation() {
        var launched = false
        val nav = object : NavigationPort {
            override fun canLaunch(destination: PlaceCandidate) = true
            override fun launch(destination: PlaceCandidate): Boolean { launched = true; return true }
        }
        val port = object : MediaPort { override fun resume() = true }
        val place = PlaceCandidate("test", "합성", Coordinates(36.2, 128.2))
        val result = ActionExecutor(nav, port).execute(PolicyDecision.Ready(place, false, mediaCommand = MediaCommand.NEXT))
        assertFalse(launched); assertFalse(result.success)
    }
    @Test fun pauseDoesNotResumeAndNextIsDispatchedOnlyOnce() {
        var resumes = 0; var pauses = 0; var skips = 0
        val nav = object : NavigationPort {
            override fun canLaunch(destination: PlaceCandidate) = false
            override fun launch(destination: PlaceCandidate) = false
        }
        val port = object : MediaPort {
            override fun canExecute(command: MediaCommand) = true
            override fun resume(): Boolean { resumes++; return true }
            override fun pause(): Boolean { pauses++; return true }
            override fun next(): Boolean { skips++; return true }
        }
        assertTrue(ActionExecutor(nav, port).execute(PolicyDecision.Ready(null, false, mediaCommand = MediaCommand.PAUSE)).success)
        assertTrue(ActionExecutor(nav, port).execute(PolicyDecision.Ready(null, false, mediaCommand = MediaCommand.NEXT)).success)
        assertEquals(0, resumes); assertEquals(1, pauses); assertEquals(1, skips)
    }
    @Test fun cancelledAndFinishedSessionsRejectLateResults() {
        val state = VoiceSessionStateMachine()
        assertTrue(state.move(InvocationState.STARTING)); assertTrue(state.move(InvocationState.LISTENING))
        assertFalse(state.move(InvocationState.EXECUTING))
        assertTrue(state.move(InvocationState.CANCELLED))
        assertFalse(state.move(InvocationState.TRANSCRIBING)); assertFalse(state.move(InvocationState.SUCCESS))
    }
    @Test fun clarificationIsBoundedToOneTurnAndExactCandidate() {
        val state = VoiceSessionStateMachine()
        for (next in listOf(InvocationState.STARTING, InvocationState.LISTENING, InvocationState.TRANSCRIBING,
            InvocationState.PLANNING, InvocationState.POLICY_CHECK)) assertTrue(state.move(next))
        assertTrue(state.clarifyOnce())
        for (next in listOf(InvocationState.LISTENING, InvocationState.TRANSCRIBING, InvocationState.PLANNING,
            InvocationState.POLICY_CHECK)) assertTrue(state.move(next))
        assertFalse(state.clarifyOnce())
        val candidates = listOf(PlaceCandidate("a", "수원역점", Coordinates(36.2, 128.2)),
            PlaceCandidate("b", "시청점", Coordinates(36.3, 128.3)))
        val choice = ClarificationRequest(ToolPlan(listOf(Action.Navigate("스타벅스"))), candidates, "어느 지점인가요?")
        assertEquals("a", choice.select("수원역점")?.id)
        assertNull(choice.select("아무 데나")); assertNull(choice.select("수원"))
    }
}
