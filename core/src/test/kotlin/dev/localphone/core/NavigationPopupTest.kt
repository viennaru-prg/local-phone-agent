package dev.localphone.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/** Startup ad popup regression fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationPopupTest {
    private val goal = "회사로 안내해줘"
    private data class Popup(val view: ScreenView, val closeNode: Int)

    private fun popup(closeLabel: String = "닫기", backgroundRoute: Boolean = false, closeId: String = "ad_close"): Popup {
        val nodes = mutableListOf(RawNode("r", -1, className="FrameLayout", bounds=Bounds(0,0,1000,2000)))
        if (backgroundRoute) {
            val map = nodes.size
            nodes += RawNode("r.map",0,className="FrameLayout",bounds=Bounds(0,0,1000,2000))
            nodes += RawNode("r.map.route",map,text="길찾기",className="Button",clickable=true,bounds=Bounds(30,300,300,400))
        }
        val panel = nodes.size
        nodes += RawNode("r.popup",0,className="FrameLayout",viewId="ad_popup",bounds=Bounds(100,400,900,1400))
        nodes += RawNode("r.popup.image",panel,desc="광고 이미지",className="ImageView",bounds=Bounds(100,500,900,1300))
        val close = nodes.size
        nodes += RawNode("r.popup.close",panel,desc=closeLabel,className="ImageButton",viewId=closeId,clickable=true,
            bounds=Bounds(810,410,895,495))
        return Popup(ScreenCompactor.compact(Snapshot(NavigationSession.NAVER_MAP,"네이버지도",nodes,1000,2000),goal),close)
    }

    private fun next(view: ScreenView): AgentAction? {
        val session = NavigationSession.forGoal(goal)!!
        session.observe(view)
        return (session.nextAction(view,0) ?: Harness.preDecide(goal,view))?.action
    }

    @BeforeTest fun clearLearning() = AppProfiles.restore(emptyMap())

    @Test fun aVisiblePopupCloseMustBeUsedWithoutCallingTheModel() {
        val p=popup()
        val close=p.view.elements.single { it.node==p.closeNode }
        assertEquals(AgentAction.Click(close.id),next(p.view), "An obstructing startup popup must be dismissed before navigation")
    }

    @Test fun underlyingRouteMustNotBePressedBeforeClosingThePopup() {
        val p=popup(backgroundRoute=true)
        val close=p.view.elements.single { it.node==p.closeNode }
        assertEquals(AgentAction.Click(close.id),next(p.view), "Do not run the underlying map's route entry through a modal ad")
    }

    @Test fun anAdDismissIconNamedDeleteMustNotBeClassifiedAsDataDeletion() {
        // The saved NAVER trees name an ad's X "삭제 · ... 광고 이미지".
        val p=popup(closeLabel="삭제")
        val close=p.view.elements.single { it.node==p.closeNode }
        assertNull(Guard.blocked(goal,AgentAction.Click(close.id),p.view), "A proven advertisement dismiss control needs its own semantic handling")
    }

    @Test fun anAccessibleUnlabeledPopupButtonMustRemainAvailableForResolution() {
        val p=popup(closeLabel="",closeId="")
        assertTrue(p.view.elements.any { it.node==p.closeNode }, "The popup's only actionable close node must not disappear from the model's element list")
    }

    @Test fun ordinaryBannersAndOtherAppsDoNotBecomeBlockingPopups() {
        val original=popup(closeLabel="삭제",backgroundRoute=true)
        val nodes=original.view.snapshot.nodes.map { n -> when(n.path) {
            "r.popup" -> n.copy(viewId="ad_banner",bounds=Bounds(100,1700,900,1900))
            "r.popup.image" -> n.copy(bounds=Bounds(100,1730,900,1890))
            "r.popup.close" -> n.copy(bounds=Bounds(810,1705,895,1785))
            else -> n
        } }
        val banner=ScreenCompactor.compact(original.view.snapshot.copy(nodes=nodes),goal)
        assertNull(NavigationPopup.find(banner.snapshot))
        assertTrue(banner.elements.any { it.label=="길찾기" })
        val delete=banner.elements.single { it.node==original.closeNode }
        assertNotNull(Guard.blocked(goal,AgentAction.Click(delete.id),banner))
        assertNull(NavigationPopup.find(original.view.snapshot.copy(packageName="example.other")))
    }

    @Test fun closeClicksDoNotBindOrAdvanceTheDestination() {
        val p=popup()
        val close=p.view.elements.single { it.node==p.closeNode }
        val session=NavigationSession.forGoal(goal)!!
        session.recordAction(p.view,AgentAction.Click(close.id),true,0)
        val old=scene("목적지: 집","안내 시작","닫기")
        session.observe(old)
        assertTrue(session.forbids(old,AgentAction.Click(old.elements.single { it.label=="안내 시작" }.id)))
        assertFalse(session.evidence.complete)
    }

    private fun scene(vararg labels: String): ScreenView {
        val nodes=listOf(RawNode("r",-1,bounds=Bounds(0,0,1000,2000)))+labels.mapIndexed { i,s ->
            RawNode("r.$i",0,text=s,clickable=!s.startsWith("목적지") && !s.startsWith("자동차"),className="Button",
                bounds=Bounds(20,80+i*160,980,180+i*160))
        }
        return ScreenCompactor.compact(Snapshot(NavigationSession.NAVER_MAP,"네이버지도",nodes,1000,2000),goal)
    }

    private inner class NavPhone(val scope: TestScope, val persistent: Boolean=false,
        val ignoredClick: Boolean=false, val lateAd: Boolean=false, val initial: Snapshot?=null): Phone {
        var phase=if(lateAd) "map" else "popup"
        val actions=mutableListOf<String>()
        override suspend fun observe()=when(phase) {
            "popup","late_popup" -> initial ?: popup(backgroundRoute=true).view.snapshot
            "map" -> scene("길찾기").snapshot
            "places" -> scene("회사").snapshot
            "preview" -> scene("목적지: 회사","자동차 5분 2km","안내 시작").snapshot
            else -> scene("목적지: 회사","경로 다시 계산").snapshot
        }
        override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
            val label=(action as? AgentAction.Click)?.let { view.element(it.id)?.label } ?: return false
            actions += "click:$label"
            when(label) {
                "광고 닫기" -> if(!persistent && !ignoredClick) phase=if(phase=="late_popup") "preview" else "map"
                "길찾기" -> { check(phase=="map"); phase="places" }
                "회사" -> { check(phase=="places"); phase=if(lateAd) "late_popup" else "preview" }
                "안내 시작" -> { check(phase=="preview"); phase="active" }
                else -> error("Unexpected action $label during $phase")
            }
            return true
        }
        override suspend fun tap(view: ScreenView,id: Int): Boolean {
            actions += "tap:${view.element(id)?.label}"
            check(NavigationPopup.isClose(view,id))
            if(!persistent) phase=if(phase=="late_popup") "preview" else "map"
            return true
        }
        override suspend fun openApp(name: String)=OpenAppResult(false,"already on map")
        override suspend fun media(key: MediaKey)=error("Navigation must not use media")
        override fun now()=scope.testScheduler.currentTime
    }

    @Test fun directDriverClosesThePopupAndFinishesTheOriginalCompanyRoute()=runTest {
        val phone=NavPhone(this)
        val session=NavigationSession.forGoal(goal)!!
        assertTrue(NavigationDriver(phone,session).confirm())
        assertEquals(listOf("click:광고 닫기","click:길찾기","click:회사","click:안내 시작"),phone.actions)
        assertTrue(session.evidence.complete)
        assertTrue(currentTime<10_000)
    }

    @Test fun screenAgentAlsoCompletesWithZeroModelCalls()=runTest {
        val phone=NavPhone(this)
        var calls=0
        val model=object:LanguageModel { override suspend fun decide(prompt: ModelPrompt,grammar: String): String { calls++; error("AI is unnecessary") } }
        val result=Agent(model,phone,RecipeBook({null},{}),{emptyList()},navigation=NavigationSession.forGoal(goal)).run(goal)
        assertEquals(Outcome.DONE,result.outcome)
        assertEquals("active",phone.phase); assertEquals(0,calls)
        assertTrue(result.history.any { it.action=="광고 팝업 확인" && it.outcome=="닫힘 확인" })
    }

    @Test fun aPopupAfterDestinationSelectionPreservesThatSelection()=runTest {
        val phone=NavPhone(this,lateAd=true)
        val model=object:LanguageModel { override suspend fun decide(prompt: ModelPrompt,grammar: String): String=error("AI is unnecessary") }
        val result=Agent(model,phone,RecipeBook({null},{}),{emptyList()},navigation=NavigationSession.forGoal(goal)).run(goal)
        assertEquals(Outcome.DONE,result.outcome)
        assertEquals(listOf("click:길찾기","click:회사","click:광고 닫기","click:안내 시작"),phone.actions)
    }

    @Test fun anAcceptedClickIsNotProofTheAdClosedAndLiveTapCanRetry()=runTest {
        val phone=NavPhone(this,ignoredClick=true)
        val result=NavigationPopupHandler(phone,goal,object:AgentListener{},PopupDismissState())
            .handle(ScreenCompactor.compact(phone.observe(),goal))
        assertTrue(result is NavigationPopupHandler.Result.Cleared)
        assertEquals(listOf("click:광고 닫기","tap:광고 닫기"),phone.actions)
    }

    @Test fun aPopupDuringARecordedRouteUsesTheSameVerifiedDismissal()=runTest {
        val phone=NavPhone(this,lateAd=true,ignoredClick=true)
        val book=RecipeBook({null},{})
        book.put(Recipe(GoalKey.of(goal),goal,listOf("길찾기","회사","안내 시작").map {
            RecipeStep("click",it,"BUTTON")
        },"길안내 시작",0,confirmed=true))
        var calls=0
        val model=object:LanguageModel { override suspend fun decide(prompt: ModelPrompt,grammar: String): String { calls++; error("AI is unnecessary") } }
        val result=Agent(model,phone,book,{emptyList()},navigation=NavigationSession.forGoal(goal)).run(goal)
        assertEquals(Outcome.DONE,result.outcome)
        assertEquals(listOf("click:길찾기","click:회사","click:광고 닫기","tap:광고 닫기","click:안내 시작"),phone.actions)
        assertEquals(0,calls)
        assertTrue(result.history.any { it.action=="광고 팝업 확인" && it.outcome=="닫힘 확인" })
    }

    @Test fun aPersistentPopupCannotFallThroughToTheModelOrBackgroundButtons()=runTest {
        val phone=NavPhone(this,persistent=true)
        val session=NavigationSession.forGoal(goal)!!
        assertFalse(NavigationDriver(phone,session).confirm())
        val count=phone.actions.size
        val model=object:LanguageModel { override suspend fun decide(prompt: ModelPrompt,grammar: String): String=error("Do not use AI on a blocked popup") }
        val result=Agent(model,phone,RecipeBook({null},{}),{emptyList()},navigation=session).run(goal)
        assertEquals(Outcome.FAILED,result.outcome)
        assertEquals(2,count); assertEquals(count,phone.actions.size)
        assertTrue(phone.actions.all { "광고 닫기" in it })
        assertTrue(currentTime<=3_000)
    }

    @Test fun ambiguousAnonymousButtonsNeverLeadToBlindClicks()=runTest {
        val p=popup(closeLabel="",closeId="")
        val second=p.view.snapshot.nodes[p.closeNode].copy(path="r.popup.other",bounds=Bounds(710,410,795,495))
        val raw=p.view.snapshot.copy(nodes=p.view.snapshot.nodes+second)
        val phone=NavPhone(this,persistent=true,initial=raw)
        val result=NavigationPopupHandler(phone,goal,object:AgentListener{},PopupDismissState()).handle(ScreenCompactor.compact(raw,goal))
        assertTrue(result is NavigationPopupHandler.Result.Failed)
        assertTrue(phone.actions.isEmpty())
    }

    @Test fun cancellationStopsPopupReadsAndClicks()=runTest {
        val phone=NavPhone(this,persistent=true)
        val handler=NavigationPopupHandler(phone,goal,object:AgentListener{},PopupDismissState())
        val job=launch { handler.handle(ScreenCompactor.compact(phone.observe(),goal)) }
        runCurrent(); job.cancelAndJoin()
        val count=phone.actions.size
        advanceTimeBy(4_000)
        assertEquals(count,phone.actions.size)
    }

    @Test fun anExplicitRequestToReadTheAdIsNotDismissed()=runTest {
        val requested="이 광고 정보 보여줘"
        val raw=popup(backgroundRoute=true).view.snapshot
        val phone=NavPhone(this,initial=raw)
        val view=ScreenCompactor.compact(raw,requested)
        assertNull(NavigationPopup.find(raw,requested))
        val handler=NavigationPopupHandler(phone,requested,object:AgentListener{},PopupDismissState())
        assertEquals(NavigationPopupHandler.Result.None,handler.handle(view))
        assertTrue(phone.actions.isEmpty())
    }
}
