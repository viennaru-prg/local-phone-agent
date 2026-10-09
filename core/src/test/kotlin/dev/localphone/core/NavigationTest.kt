package dev.localphone.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private fun navView(vararg labels: String, enabled: Boolean = true, textStart: Boolean = false,
                    pkg: String = NavigationSession.NAVER_MAP): ScreenView {
    val nodes = listOf(RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000))) + labels.mapIndexed { i, label ->
        RawNode("r.$i", 0, text = label, clickable = label.contains("시작") && !textStart,
            enabled = enabled, bounds = Bounds(0, 100 + i * 100, 1000, 190 + i * 100))
    }
    return ScreenCompactor.compact(Snapshot(pkg, "네이버 지도", nodes, 1000, 2000))
}

class NavigationTest {
    @Test fun routeSelectionUsesTheLiveOfficeShortcutWithoutALocalPlace() {
        val s = session()
        val main = navView("길찾기", "검색")
        val clickable = main.copy(elements = main.elements.map { it.copy(kind = Kind.BUTTON) })
        s.observe(clickable)
        assertEquals(AgentAction.Click(1), s.nextAction(clickable, 0)?.action)
        val slots = navView("집", "회사", "자주 가는 곳").let { it.copy(elements = it.elements.map { e -> e.copy(kind = Kind.ITEM) }) }
        s.observe(slots)
        assertEquals(AgentAction.Click(2), s.nextAction(slots, 0)?.action)
    }
    @Test fun unregisteredOrAmbiguousOfficeEntriesStayWithTheModel() {
        val s = session()
        val v = navView("회사 등록", "회사 서울", "회사 부산").let { it.copy(elements = it.elements.map { e -> e.copy(kind = Kind.ITEM) }) }
        s.observe(v)
        assertNull(s.nextAction(v, 0))
    }
    private fun session() = NavigationSession.forGoal("회사로 안내해 줘")!!

    @Test fun guidanceFromBeforeTheCommandIsEndedFromTheDrawerThenRestarted() {
        fun items(v: ScreenView) = v.copy(elements = v.elements.map { e -> e.copy(kind = Kind.ITEM) })
        val s = session()
        // The live driving screen: no destination name, only its reroute / drawer controls.
        val driving = items(navView("고매로20번길", "경로 다시 계산 (v reroute)", "메뉴·옵션 열기 (v drawer)", "8:18 PM 7.3 km"))
        s.beforeDispatch(driving)
        assertFalse(s.observe(driving).complete)
        assertEquals("ACTIVE_UNBOUND", s.evidence.state)
        assertEquals(AgentAction.Click(3), s.nextAction(driving, 0)?.action)
        val drawer = items(navView("다른 경로", "수원시 팔달구 고등동", "안내 종료", "경로 다시 계산 (v reroute)", "메뉴·옵션 열기 (v drawer)"))
        s.observe(drawer)
        assertEquals(AgentAction.Click(3), s.nextAction(drawer, 0)?.action)
        // Back on the map: the normal 길찾기 → 회사 → 안내 시작 path.
        val map = items(navView("길찾기", "검색"))
        s.observe(map)
        assertEquals(AgentAction.Click(1), s.nextAction(map, 0)?.action)
    }

    /** NAVER's route screen: a horizontal "v frequents recycler view" showing four places at a time. */
    private fun frequents(vararg places: String): ScreenView {
        val nodes = mutableListOf(
            RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000)),
            RawNode("r.0", 0, text = "도착지 입력", clickable = true, bounds = Bounds(0, 200, 1000, 300)),
            RawNode("r.1", 0, viewId = "com.nhn.android.nmap:id/v_frequents_recycler_view", scrollable = true, bounds = Bounds(0, 600, 1000, 700)),
        )
        places.forEachIndexed { i, p -> nodes += RawNode("r.1.$i", 2, text = p, clickable = true, bounds = Bounds(i * 250, 600, i * 250 + 240, 700)) }
        return ScreenCompactor.compact(Snapshot(NavigationSession.NAVER_MAP, "네이버지도", nodes, 1000, 2000))
    }

    @Test fun aFrequentPlaceBeyondTheFirstPageIsFoundByPagingTheCarousel() {
        val s = NavigationSession.forGoal("수원 집으로 안내해 줘")!!
        val first = frequents("집", "회사", "수요모…", "일요모…")
        s.observe(first)
        val scroll = s.nextAction(first, 0)?.action
        assertTrue(scroll is AgentAction.Scroll && scroll.dir == ScrollDir.RIGHT, "$scroll")
        val second = frequents("수요모…", "일요모…", "수원집")
        s.observe(second)
        val click = s.nextAction(second, 0)?.action as AgentAction.Click
        assertEquals("수원집", second.element(click.id)?.label)
    }

    @Test fun theMapsCategoryChipsAreNotPagedBeforeTheRouteEntryIsOpened() {
        val s = NavigationSession.forGoal("수원 집으로 안내해 줘")!!
        val nodes = mutableListOf(
            RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000)),
            RawNode("r.0", 0, text = "길찾기", clickable = true, bounds = Bounds(700, 100, 900, 180)),
            RawNode("r.1", 0, scrollable = true, bounds = Bounds(0, 250, 1000, 330)), // 은행 · 포장주문 · 응급진료 chips
        )
        listOf("은행", "포장주문", "응급진료", "쿠폰").forEachIndexed { i, c ->
            nodes += RawNode("r.1.$i", 2, text = c, clickable = true, bounds = Bounds(i * 250, 250, i * 250 + 240, 330))
        }
        val map = ScreenCompactor.compact(Snapshot(NavigationSession.NAVER_MAP, "네이버지도", nodes, 1000, 2000))
        s.observe(map)
        val action = s.nextAction(map, 0)?.action as AgentAction.Click
        assertEquals("길찾기", map.element(action.id)?.label)
    }

    @Test fun aMisheardPlaceIsLeftToTheModelWithEveryPlaceTheListShowed() {
        val s = NavigationSession.forGoal("수유 모임으로 안내해 줘", alternatives = listOf("수요 모임으로 안내해 줘", "수유모임으로 안내해 줘"))!!
        assertEquals(listOf("수요 모임"), s.heard)
        val first = frequents("집", "회사", "수요모…", "일요모…")
        val second = frequents("수요모…", "일요모…", "수원집")
        s.observe(first); assertTrue(s.nextAction(first, 0)?.action is AgentAction.Scroll)
        s.observe(second); assertTrue(s.nextAction(second, 0)?.action is AgentAction.Scroll)
        // End on the right: walk back to the start as well, so nothing on earlier pages is missed.
        s.observe(second); assertEquals(ScrollDir.LEFT, (s.nextAction(second, 0)?.action as AgentAction.Scroll).dir)
        s.observe(first); assertTrue(s.nextAction(first, 0)?.action is AgentAction.Scroll)
        s.observe(first); assertNull(s.nextAction(first, 0)) // both ends seen, nothing named "수유 모임"
        assertEquals(listOf("집", "회사", "수요모…", "일요모…", "수원집"), s.choiceNeeded())
        // The model judged 3 = 수요모…; the harness walks to it and presses it.
        s.choose("수요모…")
        assertNull(s.choiceNeeded())
        val click = s.nextAction(first, 0)?.action as AgentAction.Click
        assertEquals("수요모…", first.element(click.id)?.label)
        val (prompt, grammar) = Prompts.placeChoice("수유 모임으로 안내해 줘", s.heard, listOf("집", "회사", "수요모…"))
        assertTrue("수요 모임" in prompt.user && "수요모(…로 잘림)" in prompt.user, prompt.user)
        assertTrue(grammar.contains("\"수요모\"") && grammar.contains("\"없음\""), grammar)
        assertEquals("수요모…", Prompts.placePicked("{\"place\":\"수요모\"}", listOf("집", "회사", "수요모…")))
        assertEquals(null, Prompts.placePicked("{\"place\":\"없음\"}", listOf("집", "회사", "수요모…")))
    }

    @Test fun aGuessedDestinationIsConfirmedOnItsPreviewBeforeGuidance() {
        fun items(v: ScreenView) = v.copy(elements = v.elements.map { e -> e.copy(kind = Kind.ITEM) })
        val page = frequents("수요모…", "일요모…", "수원집")
        val preview = items(navView("일요모임 경기 수원시 권선구", "자동차 21분", "안내 시작"))
        val s = NavigationSession.forGoal("이름의 안내해 줘", alternatives = listOf("이름 모임 안내해 줘"))!!
        s.choose("일요모…")
        s.observe(page)
        val pick = s.nextAction(page, 0)?.action as AgentAction.Click
        s.recordAction(page, pick, true, 0)
        s.observe(preview)
        assertNull(s.nextAction(preview, 0), "no 안내 시작 before the user agrees")
        assertEquals("일요모임으로 안내할까요?", s.confirmQuestion())
        // Next run, the user said yes: the preview's start button is pressed.
        val yes = NavigationSession.forGoal("이름의 안내해 줘")!!
        yes.applyAnswer("일요모…", "응")
        yes.observe(preview)
        assertTrue(yes.isStart(preview.element((yes.nextAction(preview, 0)?.action as AgentAction.Click).id)!!.label))
        // ... or named another place: that one is looked for instead.
        val other = NavigationSession.forGoal("이름의 안내해 줘")!!
        other.applyAnswer("일요모…", "아니 수요모임")
        other.observe(page)
        assertEquals("수요모…", page.element((other.nextAction(page, 0)?.action as AgentAction.Click).id)?.label)
        assertFalse(GoalText.hasRequest("이름 모임")); assertTrue(GoalText.hasRequest("수요모임 안내해줘"))
        assertTrue(GoalText.missingDestination("안내해 줘")); assertTrue(GoalText.missingDestination("차로 안내해 줘"))
        assertFalse(GoalText.missingDestination("일요 모임으로 안내해 줘"))
        assertEquals("일요모임으로 안내해줘", GoalText.navigationGoalFrom("일요모임"))
        assertEquals("회사로 안내해줘", GoalText.navigationGoalFrom("회사로 가줘"))
    }

    @Test fun aCutNameInTheCarouselStillMatches() {
        val s = NavigationSession.forGoal("수요모임으로 안내해줘")!!
        val v = frequents("집", "회사", "수요모…", "일요모…")
        s.observe(v)
        assertEquals("수요모…", v.element((s.nextAction(v, 0)?.action as AgentAction.Click).id)?.label)
    }

    @Test fun theCarouselEndStopsPaging() {
        val s = NavigationSession.forGoal("부산 별장으로 안내해줘")!!
        val v = frequents("집", "회사")
        s.observe(v); assertTrue(s.nextAction(v, 0)?.action is AgentAction.Scroll)
        s.observe(v); assertEquals(ScrollDir.LEFT, (s.nextAction(v, 0)?.action as AgentAction.Scroll).dir) // right end: back to the start
        s.observe(v); assertNull(s.nextAction(v, 0)) // both ends: the list is done
        assertEquals(listOf("집", "회사"), s.choiceNeeded())
    }

    @Test fun endGuidanceOpensTheDrawerPressesEndAndConfirmsTheDrivingScreenIsGone() {
        fun items(v: ScreenView) = v.copy(elements = v.elements.map { e -> e.copy(kind = Kind.ITEM) })
        val goal = "길안내 종료해줘"
        assertTrue(Harness.isEndGuidance(goal)); assertFalse(Harness.isEndGuidance("회사로 안내해줘"))
        val driving = items(navView("고매로20번길", "경로 다시 계산 (v reroute)", "메뉴·옵션 열기 (v drawer)"))
        assertEquals(AgentAction.Click(3), Harness.preDecide(goal, driving)?.action)
        val drawer = items(navView("다른 경로", "안내 종료", "경로 다시 계산 (v reroute)", "메뉴·옵션 열기 (v drawer)"))
        val opened = listOf(HistoryLine("click \"메뉴·옵션 열기 (v drawer)\"", "화면 바뀜"))
        assertEquals(AgentAction.Click(2), Harness.preDecide(goal, drawer, opened)?.action)
        val ended = opened + HistoryLine("click \"안내 종료\"", "화면 바뀜")
        assertTrue(Harness.guidanceEnded(goal, items(navView("길찾기", "검색", "음식점", "카페", "편의점")), ended))
        assertFalse(Harness.guidanceEnded(goal, driving, opened))
    }

    @Test fun theRouteThisCommandStartedIsNeverEnded() {
        fun items(v: ScreenView) = v.copy(elements = v.elements.map { e -> e.copy(kind = Kind.ITEM) })
        val s = session()
        s.recordAction(items(navView("회사")), AgentAction.Click(1), true, 0)
        s.observe(navView("자동차 32분", "안내 시작"))
        val driving = items(navView("고매로20번길", "경로 다시 계산 (v reroute)", "메뉴·옵션 열기 (v drawer)"))
        assertTrue(s.observe(driving).complete)
        assertNull(s.nextAction(driving, 0))
    }

    @Test fun etaAndStartButtonArePreviewNotCompletion() {
        val s = session()
        assertFalse(s.observe(navView("회사", "안내 시작", "도착 예정 12:30", "남은 거리 10km")).complete)
        assertEquals("PREVIEW", s.evidence.state)
    }
    @Test fun oldGuidanceDoesNotProveNewDestination() {
        val s = session()
        val before = navView("목적지: 부산역", "안내 종료", "남은 거리 10km")
        s.beforeDispatch(before)
        assertFalse(s.observe(before).complete)
        assertEquals("WRONG_DESTINATION", s.evidence.state)
    }
    @Test fun sameOldGuidanceWithMatchingNameMustChangeAfterDispatch() {
        val s = session(); val view = navView("회사", "안내 종료")
        s.beforeDispatch(view)
        assertFalse(s.observe(view).complete)
    }
    @Test fun selectedDestinationBindsPreviewThenGuidanceWithoutAlias() {
        val s = session(); val saved = navView("회사 서울 중구")
        s.recordAction(saved, AgentAction.Click(1), true, 0)
        assertFalse(s.observe(navView("자동차 32분", "안내 시작")).complete)
        assertTrue(s.observe(navView("안내 종료", "남은 거리 10km")).complete)
    }
    @Test fun anotherDestinationBreaksTheSelectionBinding() {
        val s = session(); s.observe(navView("회사", "안내 시작"))
        assertFalse(s.observe(navView("목적지: 서울역", "안내 종료")).complete)
    }
    @Test fun countdownAndDisabledButtonAreNotCompletedOrClicked() {
        val s = session(); val v = navView("회사", "안내 시작 5초 후 자동", enabled = false)
        assertFalse(s.observe(v).complete)
        assertTrue(s.nextAction(v, 0)?.action is AgentAction.Wait)
    }
    @Test fun acceptedClickWithNoTransitionIsRetriedAsObservedTap() = runTest {
        val phone = NavPhone(this, ignoreClick = true)
        val s = session(); s.beforeDispatch(null)
        assertTrue(NavigationDriver(phone, s).confirm())
        assertEquals(1, phone.clicks); assertEquals(1, phone.taps)
    }
    @Test fun timeoutDoesNotClaimSuccess() = runTest {
        val phone = NavPhone(this, ignoreClick = true, ignoreTap = true)
        val s = session(); s.beforeDispatch(null)
        assertFalse(NavigationDriver(phone, s).confirm(2500))
        assertFalse(s.evidence.complete)
    }
    @Test fun stableTextOnlyStartOnComputedRouteUsesGesture() = runTest {
        val phone = NavPhone(this, textStart = true)
        val s = session(); s.beforeDispatch(null)
        assertTrue(NavigationDriver(phone, s).confirm())
        assertEquals(0, phone.clicks); assertEquals(1, phone.taps)
    }
    @Test fun aDifferentAppCannotProveNavigation() {
        assertFalse(session().observe(navView("会社", "안내 종료", pkg = "other")).complete)
    }
    @Test fun spacedAndCompactGoalsIdentifyTheSamePersonalPlace() {
        for (goal in listOf("회사로 안내해 줘", "회사로 안내해줘", "직장으로 가자", "네이버 지도에서 회사로 안내해줘")) {
            val s = NavigationSession.forGoal(goal)!!
            assertFalse(s.observe(navView("회사", "안내 시작")).complete)
            assertTrue(s.observe(navView("안내 종료")).complete, goal)
        }
    }
    private class NavPhone(val scope: TestScope, val ignoreClick: Boolean = false, val ignoreTap: Boolean = false,
                           val textStart: Boolean = false) : Phone {
        var driving = false; var clicks = 0; var taps = 0
        override suspend fun observe() = (if (driving) navView("안내 종료") else navView("회사", "자동차 32분", "안내 시작", textStart = textStart)).snapshot
        override suspend fun perform(view: ScreenView, action: AgentAction): Boolean { clicks++; if (!ignoreClick) driving = true; return true }
        override suspend fun tap(view: ScreenView, id: Int): Boolean { taps++; if (!ignoreTap) driving = true; return true }
        override suspend fun openApp(name: String) = OpenAppResult(false, "")
        override suspend fun media(key: MediaKey) = error("navigation must never touch media")
        override fun now() = scope.testScheduler.currentTime
    }
}
