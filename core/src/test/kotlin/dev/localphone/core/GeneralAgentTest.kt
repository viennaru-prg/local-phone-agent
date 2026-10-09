package dev.localphone.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GeneralAgentTest {
    @Test fun aCompoundGoalIsNotSummarizedAsOnlyOpeningAScreen() {
        val say = "통계 화면을 열고 고양이 항목을 검색했어요"
        assertEquals(say, GoalText.spokenResult("도구상자에서 통계 화면 열고 고양이 검색해줘", say))
        val goal = "임의플레이어에서 고양이 검색하고 첫 결과를 재생해줘"
        assertEquals(2, GoalScope.parts(goal).size)
        val resultList = ScreenCompactor.compact(screen("플레이어", RawNode("r.0", 0, text = "고양이 검색 결과", bounds = Bounds(0, 100, 1000, 200))))
        assertTrue(Prompts.doneEvidence(goal, listOf(HistoryLine("type 검색", "화면 바뀜")), resultList).isEmpty())
    }
    @Test fun aMenuTitleCannotProveAnUnselectedTabWasOpened() {
        val goal = "도구상자에서 통계 화면 열어줘"
        val menu = ScreenCompactor.compact(screen("도구상자", button("통계")))
        assertFalse(Harness.openScreenEvidence(goal, menu))
        val selected = ScreenCompactor.compact(screen("도구상자", button("통계").copy(selected = true)))
        assertTrue(Harness.openScreenEvidence(goal, selected))
    }
    @Test fun iconResourceHintsDescribeAffordancesInAnUnknownApp() {
        val view = ScreenCompactor.compact(screen("임의 앱", button("").copy(viewId = "example:id/btnDrawer"),
            button("").copy(path = "r.1", viewId = "example:id/btn_refresh", bounds = Bounds(0, 300, 1000, 400))))
        assertTrue(view.elements.any { it.label.contains("메뉴·옵션") })
        assertTrue(view.elements.any { it.label.contains("새로고침") })
    }
    private fun screen(label: String, vararg nodes: RawNode) = Snapshot("example.general", label,
        listOf(RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000))) + nodes, 1000, 2000)
    private fun button(text: String, enabled: Boolean = true) = RawNode("r.0", 0, text = text,
        clickable = true, enabled = enabled, className = "Button", bounds = Bounds(0, 100, 1000, 200))

    @Test fun disabledControlsAreExcludedAndCannotBeDispatched() {
        val view = ScreenCompactor.compact(screen("임의 앱", button("종료", false),
            button("메뉴").copy(path = "r.1", bounds = Bounds(0, 300, 1000, 400))))
        assertNotNull(Guard.blocked("현재 작업을 끝내줘", AgentAction.Click(1), view))
        assertTrue("id ::= \"2\"" in ActionGrammar.forView(view))
    }

    @Test fun doneWithoutActionsStillRequiresVerification() = runTest {
        var verifies = 0
        val model = object : LanguageModel {
            override suspend fun decide(prompt: ModelPrompt, grammar: String): String {
                if ("완료 확인" in prompt.user) { verifies++; return """{"ok":false,"reason":"작업은 아직 진행 중"}""" }
                return """{"action":"done","say":"끝냈어요"}"""
            }
        }
        val phone = fixedPhone(screen("임의 앱", button("계속 실행 중")))
        val result = Agent(model, phone, RecipeBook({ null }, {}), { emptyList() }).run("현재 작업을 중단해줘")
        assertEquals(Outcome.FAILED, result.outcome)
        assertEquals(2, verifies)
    }

    @Test fun changingCounterDoesNotJustifyRepeatingAnIneffectiveClick() = runTest {
        var clicks = 0
        val phone = object : Phone {
            override suspend fun observe() = screen("임의 앱", button("다른 동작"), RawNode("r.1", 0,
                text = "$clicks 초 경과", bounds = Bounds(0, 500, 1000, 600)))
            override suspend fun perform(view: ScreenView, action: AgentAction): Boolean { clicks++; return true }
            override suspend fun openApp(name: String) = OpenAppResult(false, "없음")
            override suspend fun media(key: MediaKey) = false
            override fun now() = testScheduler.currentTime
        }
        val model = object : LanguageModel { override suspend fun decide(prompt: ModelPrompt, grammar: String) = """{"action":"click","id":1}""" }
        val result = Agent(model, phone, RecipeBook({ null }, {}), { emptyList() }, AgentConfig(maxNoChange = 2)).run("작업을 중단해줘")
        assertEquals(Outcome.FAILED, result.outcome)
        assertEquals(1, clicks, "counter changes must not enable the same failed action again")
        assertTrue(result.history.any { "동적 텍스트" in it.outcome })
    }

    @Test fun aCompoundSearchGoalContinuesUntilPlaybackAndIsNotReportedAsOnlySearch() = runTest {
        var playing = false
        var queried = false
        val phone = object : Phone {
            override suspend fun observe() = if (!queried) screen("플레이어", RawNode("r.0", 0, hint = "검색",
                editable = true, className = "EditText", bounds = Bounds(0, 100, 1000, 200)))
            else if (!playing) screen("플레이어", button("재생"), RawNode("r.1", 0, text = "고양이 검색 결과", bounds = Bounds(0, 500, 1000, 600)))
            else screen("플레이어", button("일시 정지"), RawNode("r.1", 0, text = "고양이 재생 중", bounds = Bounds(0, 500, 1000, 600)))
            override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
                if (action is AgentAction.Type) queried = true
                if (action is AgentAction.Click) playing = true
                return true
            }
            override suspend fun openApp(name: String) = OpenAppResult(true, "이미 열려 있음")
            override suspend fun media(key: MediaKey) = false
            override fun now() = testScheduler.currentTime
        }
        val model = object : LanguageModel {
            override suspend fun decide(prompt: ModelPrompt, grammar: String): String {
                if ("완료 확인" in prompt.user) {
                    assertTrue(playing, "Do not ask a completion-biased model to accept the intermediate search submission")
                    return """{"ok":true,"reason":"고양이를 검색하고 재생했어요"}"""
                }
                return """{"action":"click","id":1}"""
            }
        }
        val result = Agent(model, phone, RecipeBook({ null }, {}), { emptyList() }).run("플레이어에서 고양이 검색하고 첫 결과를 재생해줘")
        assertEquals(Outcome.DONE, result.outcome)
        assertTrue(playing)
        assertTrue("재생" in result.say)
    }

    @Test fun aMultiGoalCommandDoesNotTakeASingleNavigationShortcut() {
        val book = PlaceBook({ null }, {}).apply { put(Place("회사", lat = 37.5, lng = 127.0)) }
        for (goal in listOf("회사로 안내하고 음악 재생해줘", "회사로 가고 음악 틀어줘")) {
            assertTrue(GoalScope.multiple(goal))
            assertNull(Router.quick(goal, book))
            assertNull(NavigationSession.forGoal(goal))
            assertTrue(Router.allowsMedia(goal), "the screen agent may perform the explicitly requested music part")
        }
        assertFalse(Router.allowsMedia("네비 꺼줘"))
        assertFalse(Router.allowsMedia("타이머 멈춰줘"), "a generic stop verb must not force a music key")
        assertFalse(Router.allowsMedia("다음 알람을 보여줘"))
        assertTrue(Router.allowsMedia("음악 멈춰줘"))
    }

    @Test fun aDecisionSurvivesUnrelatedDynamicRowsButBindsToTheLiveMenuId() {
        fun snapshot(road: String, insert: Boolean) = screen("임의 앱",
            button(road).copy(path = "r.0", viewId = "example:id/live_status"),
            button("메뉴").copy(path = "r.1", viewId = "example:id/menu", bounds = Bounds(0, 600, 1000, 700)),
            RawNode("r.2", 0, text = if (insert) "새 알림" else "", bounds = Bounds(0, 300, 1000, 400)))
        val before = ScreenCompactor.compact(snapshot("12초 남음", false))
        val live = ScreenCompactor.compact(snapshot("11초 남음", true))
        val oldMenu = before.elements.single { it.label == "메뉴" }
        val newMenu = live.elements.single { it.label == "메뉴" }
        assertNotEquals(oldMenu.id, newMenu.id)
        assertEquals(AgentAction.Click(newMenu.id), ActionGrounding.rebind(AgentAction.Click(oldMenu.id), before, live))
    }

    @Test fun aDecisionIsRejectedIfItsTargetOrForegroundAppChanged() {
        val before = ScreenCompactor.compact(screen("임의 앱", button("끝내기").copy(viewId = "example:id/commit")))
        val replaced = ScreenCompactor.compact(screen("임의 앱", button("다시 시작").copy(viewId = "example:id/commit")))
        assertNull(ActionGrounding.rebind(AgentAction.Click(1), before, replaced))
        assertNull(ActionGrounding.rebind(AgentAction.Click(1), before, before.copy(snapshot = before.snapshot.copy(packageName = "example.other"))))
    }

    @Test fun anUnexpectedForegroundSwitchDoesNotRedirectTheGoalToAnotherApp() = runTest {
        var switched = false
        var dispatched = 0
        val phone = object : Phone {
            override suspend fun observe() = screen("임의 앱", button("메뉴")).let {
                if (switched) it.copy(packageName = "example.unrelated") else it
            }
            override suspend fun perform(view: ScreenView, action: AgentAction): Boolean { dispatched++; return true }
            override suspend fun openApp(name: String) = OpenAppResult(false, "없음")
            override suspend fun media(key: MediaKey) = false
            override fun now() = testScheduler.currentTime
        }
        val model = object : LanguageModel {
            override suspend fun decide(prompt: ModelPrompt, grammar: String): String {
                switched = true
                return """{"action":"click","id":1}"""
            }
        }
        val result = Agent(model, phone, RecipeBook({null}, {}), {emptyList()}).run("현재 작업을 꺼줘")
        assertEquals(Outcome.FAILED, result.outcome)
        assertEquals(0, dispatched)
        assertTrue(result.say.contains("다른 앱"))
    }

    private fun fixedPhone(snapshot: Snapshot) = object : Phone {
        override suspend fun observe() = snapshot
        override suspend fun perform(view: ScreenView, action: AgentAction) = false
        override suspend fun openApp(name: String) = OpenAppResult(false, "")
        override suspend fun media(key: MediaKey) = false
        override fun now() = 0L
    }

    @Test fun aTerminationActionChecksCompletionBeforeAnyFurtherExploration() = runTest {
        var stopped = false
        var decisions = 0
        var verifications = 0
        val phone = object : Phone {
            override suspend fun observe() = if (!stopped) screen("임의 앱", button("끝내기"))
                else screen("임의 앱", RawNode("r.0", 0, text = "일반 화면", bounds = Bounds(0, 100, 1000, 200)))
            override suspend fun perform(view: ScreenView, action: AgentAction): Boolean { stopped = true; return true }
            override suspend fun openApp(name: String) = OpenAppResult(false, "없음")
            override suspend fun media(key: MediaKey) = false
            override fun now() = testScheduler.currentTime
        }
        val model = object : LanguageModel {
            override suspend fun decide(prompt: ModelPrompt, grammar: String): String {
                if (prompt.user.contains("완료 확인")) { verifications++; assertTrue(stopped); return """{"ok":true,"reason":"진행 중인 작업을 종료했어요"}""" }
                decisions++
                return """{"action":"click","id":1}"""
            }
        }
        val result = Agent(model, phone, RecipeBook({null}, {}), {emptyList()}).run("현재 작업을 꺼줘")
        assertEquals(Outcome.DONE, result.outcome)
        assertEquals(1, decisions, "Do not keep looking for an exit after the task was stopped")
        assertEquals(0, verifications, "The actual atomic termination uses its observed postcondition")
        assertTrue(result.history.any { it.action=="완료 확인" && it.outcome=="목표 달성" })
    }
}
