package dev.localphone.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OpenGoalTest {
    private fun screen(vararg labels: String, selected: Boolean = false) = Snapshot("example.unknown", "임의 앱",
        listOf(RawNode("r",-1,bounds=Bounds(0,0,1000,2400))) + labels.mapIndexed { i, label ->
            RawNode("r.$i",0,text=label,clickable=true,className="Button",selected=selected,bounds=Bounds(0,100+i*200,1000,200+i*200))
        },1000,2400)
    private fun book() = RecipeBook({null}, {})

    @Test fun complexNavigationRequestsCannotBeReducedToStartingGuidanceToAMentionedPlace() {
        val places = PlaceBook({null}, {}).apply { put(Place("회사",lat=37.1,lng=127.1)) }
        for (goal in listOf("회사로 목적지 바꿔줘", "회사 가는 길에 가까운 주유소 찾아줘", "안내 유지하면서 다른 경로 보여줘",
            "회사 들렀다가 집으로 안내해줘", "회사로 통행료 없이 안내해줘", "회사까지 가되 계단은 피해줘",
            "네비에서 제보 화면 열고 위험 지점을 표시해줘", "네비의 안내 음량을 낮춰줘", "회사 주차장 입구를 비교해줘")) {
            assertNull(Router.quick(goal,places),goal)
            assertNull(NavigationSession.forGoal(goal,places.mentionedIn(goal)),goal)
        }
        assertNotNull(Router.quick("회사로 안내해 줘",places))
    }

    @Test fun mentioningMusicDoesNotAllowASubsetMediaShortcut() {
        for (goal in listOf("노래 재생목록에 추가해줘", "음악 볼륨 낮춰줘", "음악 추천 화면 열어줘", "다음 곡 이름 알려줘"))
            assertNull(Router.simpleMediaKey(goal),goal)
        assertEquals(MediaKey.PAUSE,Router.simpleMediaKey("음악 멈춰줘"))
    }

    @Test fun qualifiedFindingAndInformationRequestsAreNotAutomaticSearchOrScreenCompletion() {
        for (goal in listOf("가까운 24시간 약국 찾아줘", "도착 시간을 비교해서 보여줘", "현재 작업 유지하고 대안을 보여줘")) {
            val view=ScreenCompactor.compact(screen("검색",goal,"touch outside"))
            assertNull(Harness.preDecide(goal,view,listOf(HistoryLine("open_app", "열림"))),goal)
            assertFalse(Harness.openScreenEvidence(goal,view),goal)
        }
    }

    @Test fun anyControlCanFinishAGoalWhenTheModelPredictsAndVerifiesTheResult() = runTest {
        var changed=false
        var decides=0
        var verifies=0
        val phone=object: Phone {
            override suspend fun observe()=screen("다른 방식",selected=changed)
            override suspend fun perform(view:ScreenView,action:AgentAction):Boolean { changed=true; return true }
            override suspend fun openApp(name:String)=OpenAppResult(false,"")
            override suspend fun media(key:MediaKey)=false
            override fun now()=testScheduler.currentTime
        }
        val model=object: LanguageModel {
            override suspend fun decide(prompt:ModelPrompt,grammar:String):String {
                if ("완료 확인" in prompt.user) { verifies++; assertTrue(changed); return """{"ok":true,"reason":"다른 방식을 선택했어요"}""" }
                decides++
                return """{"expect":"다른 방식 선택됨","check":true,"action":"click","id":1}"""
            }
        }
        assertFalse(Commit.isCommit("다른 방식"))
        val result=Agent(model,phone,book(),{emptyList()}).run("다른 방식으로 해줘")
        assertEquals(Outcome.DONE,result.outcome)
        assertEquals(1,decides)
        assertEquals(1,verifies)
    }

    @Test fun anExpectedChangeCannotProveSuccessWhenTheScreenDidNotChange() = runTest {
        var changed=false
        val phone=object: Phone {
            override suspend fun observe()=screen("옵션",selected=changed)
            override suspend fun perform(view:ScreenView,action:AgentAction)=true
            override suspend fun openApp(name:String)=OpenAppResult(false,"")
            override suspend fun media(key:MediaKey)=false
            override fun now()=testScheduler.currentTime
        }
        val model=object: LanguageModel {
            override suspend fun decide(prompt:ModelPrompt,grammar:String)= if ("완료 확인" in prompt.user)
                """{"ok":false,"reason":"아직 이전 상태"}""" else """{"expect":"옵션 적용됨","check":true,"action":"click","id":1}"""
        }
        assertEquals(Outcome.FAILED,Agent(model,phone,book(),{emptyList()},AgentConfig(maxNoChange=2)).run("옵션을 적용해줘").outcome)
    }

    @Test fun prematureQuestionsAreReviewedAndTheRemainingMenuPathIsExecuted() = runTest {
        var phase=0
        var asked=false
        var reviewed=false
        val phone=object: Phone {
            override suspend fun observe()=screen(if (phase==0) "메뉴" else "옵션",selected=phase==2)
            override suspend fun perform(view:ScreenView,action:AgentAction):Boolean { phase++; return true }
            override suspend fun openApp(name:String)=OpenAppResult(false,"")
            override suspend fun media(key:MediaKey)=false
            override fun now()=testScheduler.currentTime
        }
        val model=object: LanguageModel {
            override suspend fun decide(prompt:ModelPrompt,grammar:String):String = when {
                "중단 재검토" in prompt.user -> { reviewed=true; """{"verdict":"continue","reason":"메뉴에서 옵션을 찾을 수 있음"}""" }
                "완료 확인" in prompt.user -> { assertEquals(2,phase); """{"ok":true,"reason":"옵션을 적용했어요"}""" }
                !asked -> { asked=true; """{"action":"ask","question":"설정을 등록해 주세요"}""" }
                else -> """{"expect":"옵션 선택","check":${phase==1},"action":"click","id":1}"""
            }
        }
        val result=Agent(model,phone,book(),{emptyList()}).run("새 옵션을 적용해줘")
        assertEquals(Outcome.DONE,result.outcome)
        assertTrue(reviewed)
        assertEquals(2,phase)
    }

    @Test fun genuineAmbiguityIsStillAskedBeforeSelectingACandidate() = runTest {
        var clicks=0
        val phone=object: Phone {
            override suspend fun observe()=screen("같은 이름 A", "같은 이름 B")
            override suspend fun perform(view:ScreenView,action:AgentAction):Boolean { clicks++; return true }
            override suspend fun openApp(name:String)=OpenAppResult(false,"")
            override suspend fun media(key:MediaKey)=false
            override fun now()=testScheduler.currentTime
        }
        val model=object: LanguageModel {
            override suspend fun decide(prompt:ModelPrompt,grammar:String)= if ("중단 재검토" in prompt.user)
                """{"verdict":"ask","reason":"같은 이름의 후보 두 곳을 사용자가 구분해야 함"}""" else
                """{"action":"ask","question":"A와 B 중 어느 곳인가요?"}"""
        }
        val result=Agent(model,phone,book(),{emptyList()}).run("같은 이름 항목을 선택해줘")
        assertEquals(Outcome.ASK,result.outcome)
        assertEquals(0,clicks)
    }

    @Test fun inspectionCanExposeAnOmittedControlAndUseItsFreshId() = runTest {
        var selected=false
        val noise=(0..99).map { i -> RawNode("r.$i",0,text="항목 $i",clickable=true,className="Button",bounds=Bounds(0,10+i*10,1000,18+i*10)) }
        val phone=object: Phone {
            override suspend fun observe()=Snapshot("example.unknown","임의 앱",listOf(RawNode("r",-1,bounds=Bounds(0,0,1000,2400))) + noise +
                RawNode("r.100",0,text="자동 연장",selected=selected,clickable=true,className="Button",bounds=Bounds(0,1800,1000,1900)),1000,2400)
            override suspend fun perform(view:ScreenView,action:AgentAction):Boolean {
                assertEquals("자동 연장",view.element((action as AgentAction.Click).id)?.label)
                selected=true; return true
            }
            override suspend fun openApp(name:String)=OpenAppResult(false,"")
            override suspend fun media(key:MediaKey)=false
            override fun now()=testScheduler.currentTime
        }
        var inspected=false
        val model=object: LanguageModel {
            override suspend fun decide(prompt:ModelPrompt,grammar:String):String {
                if ("완료 확인" in prompt.user) return """{"ok":true,"reason":"만료 방지 옵션을 켰어요"}"""
                if (!inspected) { inspected=true; return """{"action":"inspect","query":"자동 연장"}""" }
                val id=Regex("\\[(\\d+)] 버튼 \\\"자동 연장").find(prompt.user)!!.groupValues[1]
                return """{"expect":"옵션 켜짐","check":true,"action":"click","id":$id}"""
            }
        }
        val goal="항목이 만료되지 않게 해줘"
        assertTrue(ScreenCompactor.compact(phone.observe(),goal).elements.none { it.label=="자동 연장" })
        assertEquals(Outcome.DONE,Agent(model,phone,book(),{emptyList()}).run(goal).outcome)
        assertTrue(selected)
    }

    @Test fun horizontalGesturesStayInsideTheObservedShortContainer() {
        val b=Bounds(-50,400,1200,560)
        val right=ScrollGesture.path(b,1000,2400,ScrollDir.RIGHT)!!
        assertEquals(480f,right.y1)
        assertTrue(right.x1>right.x2)
        assertTrue(right.x1 in 0f..1000f && right.x2 in 0f..1000f)
        assertNull(ScrollGesture.path(Bounds(1100,0,1500,500),1000,2400,ScrollDir.RIGHT))
    }

    @Test fun generalInformationAnswersKeepObservedNumbersAndConditions() {
        val goal="조건을 비교하고 차이를 알려줘"
        val say="현재 방식은 23분, 다른 방식은 28분이며 5분 더 걸립니다. 현재 설정은 유지했습니다."
        assertEquals(say,GoalText.spokenResult(goal,say))
    }

    @Test fun comparisonEvidenceSurvivesLeavingTheCandidateList() {
        val facts=ObservedFacts()
        facts.observe(ScreenCompactor.compact(screen("A 4분", "B 7분")))
        facts.observe(ScreenCompactor.compact(screen("B 상세")))
        val prompt=Prompts.verify("시간이 적은 항목을 선택해줘",emptyList(),emptyList(),ScreenCompactor.compact(screen("B 상세")),facts=facts.lines())
        assertTrue("A 4분" in prompt.user)
        assertTrue("B 7분" in prompt.user)
        assertTrue("과거 화면도 포함" in prompt.user)
    }

    @Test fun aSelectionChangeChecksTheWholeGoalEvenWhenTheModelMissesTheCheckpoint() = runTest {
        var selected=false;var decisions=0
        val phone=object:Phone {
            override suspend fun observe()=screen("크게",selected=selected)
            override suspend fun perform(view:ScreenView,action:AgentAction):Boolean { selected=true;return true }
            override suspend fun openApp(name:String)=OpenAppResult(false,"")
            override suspend fun media(key:MediaKey)=false
            override fun now()=testScheduler.currentTime
        }
        val model=object:LanguageModel {
            override suspend fun decide(prompt:ModelPrompt,grammar:String):String {
                if("완료 확인" in prompt.user) { assertTrue(selected);return """{"ok":true,"reason":"글자 크기를 변경했어요"}""" }
                decisions++;return """{"expect":"글자 크기 변경","check":false,"action":"click","id":1}"""
            }
        }
        assertEquals(Outcome.DONE,Agent(model,phone,book(),{emptyList()}).run("글자 크기를 크게 바꿔줘").outcome)
        assertEquals(1,decisions)
    }

    @Test fun repeatedResourceIdsDoNotEraseDifferentCandidatesAndDeselectionUpdatesFacts() {
        val initial=screen("A 4분","B 7분").let { s -> s.copy(nodes=s.nodes.mapIndexed { i,n ->
            if(i==0) n else n.copy(viewId="example:id/item",selected=i==1)
        }) }
        val facts=ObservedFacts();facts.observe(ScreenCompactor.compact(initial))
        assertEquals(2,facts.lines().size)
        facts.observe(ScreenCompactor.compact(initial.copy(nodes=initial.nodes.map { it.copy(selected=false) })))
        assertTrue(facts.lines().all { "미선택" in it })
    }

    @Test fun anAvailableActionIsNotEvidenceThatTheActionWasPerformed() {
        val goal="경유지로 넣어줘"
        val detail=ScreenCompactor.compact(screen("경유지").copy(nodes=listOf(
            RawNode("r",-1,bounds=Bounds(0,0,1000,2400)),
            RawNode("r.0",0,text="B 주유소",bounds=Bounds(0,100,1000,200)),
            RawNode("r.1",0,text="경유지",clickable=true,className="Button",bounds=Bounds(0,300,1000,400)))))
        assertFalse(CompletionGrounding.hasOutcome(goal,detail,emptyList()))
        val applied=ScreenCompactor.compact(detail.snapshot.copy(nodes=detail.snapshot.nodes+
            RawNode("r.2",0,text="경유지 B 주유소",bounds=Bounds(0,500,1000,600))))
        assertTrue(CompletionGrounding.hasOutcome(goal,applied,emptyList()))
    }

    @Test fun aFailedTapDoesNotRemoveTheLongPressAlternative() {
        val view=ScreenCompactor.compact(screen("항목"))
        val grammar=ActionGrammar.forView(view,excludedIds=setOf(1),excludedLongIds=emptySet(),excludedOps=setOf("back","done"))
        val root=grammar.lineSequence().first()
        assertFalse(" | click" in root)
        assertTrue("longclick" in root)
        assertFalse(" | done" in root)
        assertTrue("lid ::= \"1\"" in grammar)
    }

    @Test fun repeatingInspectionWithoutNewInformationDoesNotExtendTheTaskForever() = runTest {
        var calls=0
        val phone=object:Phone {
            override suspend fun observe()=screen("보이는 항목")
            override suspend fun perform(view:ScreenView,action:AgentAction)=error("Read-only inspection must not dispatch")
            override suspend fun openApp(name:String)=OpenAppResult(false,"")
            override suspend fun media(key:MediaKey)=false
            override fun now()=testScheduler.currentTime
        }
        val model=object:LanguageModel {
            override suspend fun decide(prompt:ModelPrompt,grammar:String):String {
                calls++;return """{"action":"inspect","query":"같은 정보"}"""
            }
        }
        val result=Agent(model,phone,book(),{emptyList()},AgentConfig(maxNoChange=2)).run("다른 정보를 찾아줘")
        assertEquals(Outcome.FAILED,result.outcome)
        assertEquals(2,calls)
    }

    @Test fun anInformationAnswerIsVerifiedAgainstTheObservedValuesBeforeBeingSpoken() = runTest {
        val phone=object:Phone {
            override suspend fun observe()=screen("첫 안 12분","다른 안 19분",selected=true)
            override suspend fun perform(view:ScreenView,action:AgentAction)=error("Information was already visible")
            override suspend fun openApp(name:String)=OpenAppResult(false,"")
            override suspend fun media(key:MediaKey)=false
            override fun now()=testScheduler.currentTime
        }
        val model=object:LanguageModel {
            override suspend fun decide(prompt:ModelPrompt,grammar:String):String {
                if("완료 확인" in prompt.user) {
                    assertTrue("답변 후보" in prompt.user && "7분" in prompt.user)
                    assertTrue("12분" in prompt.user && "19분" in prompt.user)
                    return """{"ok":true,"reason":"7분 차이이며 선택을 유지했어요"}"""
                }
                return """{"action":"done","say":"7분 차이이며 선택을 유지했어요"}"""
            }
        }
        val result=Agent(model,phone,book(),{emptyList()}).run("두 안의 차이를 알려주고 현재 선택은 유지해줘")
        assertEquals(Outcome.DONE,result.outcome)
        assertTrue("7분" in result.say)
    }

    @Test fun newlyObservedOutcomeDataTriggersFullVerificationWithoutARecognizedCommitButton() = runTest {
        var added=false;var calls=0
        val phone=object:Phone {
            override suspend fun observe()=if(!added) screen("이 방식") else screen("이 방식").copy(nodes=listOf(
                RawNode("r",-1,bounds=Bounds(0,0,1000,2400)),
                RawNode("r.0",0,text="새 대상이 목록에 있음",bounds=Bounds(0,100,1000,200))))
            override suspend fun perform(view:ScreenView,action:AgentAction):Boolean { added=true;return true }
            override suspend fun openApp(name:String)=OpenAppResult(false,"")
            override suspend fun media(key:MediaKey)=false
            override fun now()=testScheduler.currentTime
        }
        val model=object:LanguageModel {
            override suspend fun decide(prompt:ModelPrompt,grammar:String):String {
                if("완료 확인" in prompt.user) { assertTrue(added);return """{"ok":true,"reason":"목록에 대상을 넣었어요"}""" }
                calls++;return """{"action":"click","id":1,"check":false}"""
            }
        }
        assertFalse(Commit.isCommit("이 방식"))
        assertEquals(Outcome.DONE,Agent(model,phone,book(),{emptyList()}).run("새 대상을 목록에 넣어줘").outcome)
        assertEquals(1,calls)
    }

    @Test fun merelyOpeningAChoiceScreenCannotProveThatTheRequestedValueWasApplied() {
        val snapshot=screen("작게","크게").let { s -> s.copy(nodes=s.nodes.mapIndexed { i,node -> node.copy(selected=i==1) }) }
        val view=ScreenCompactor.compact(snapshot)
        assertNotNull(CompletionGrounding.conflictingChoice("글자 크기를 크게 바꿔줘",view))
        assertFalse(CompletionGrounding.hasOutcome("글자 크기를 크게 바꿔줘",view,emptyList()))
        val applied=ScreenCompactor.compact(snapshot.copy(nodes=snapshot.nodes.mapIndexed { i,node -> node.copy(selected=i==2) }))
        assertNull(CompletionGrounding.conflictingChoice("글자 크기를 크게 바꿔줘",applied))
        assertTrue(CompletionGrounding.hasOutcome("글자 크기를 크게 바꿔줘",applied,emptyList()))
        assertNull(CompletionGrounding.conflictingChoice("작게와 크게의 차이를 알려주고 선택은 그대로 둬",view))
    }

    @Test fun modelSuccessProofsMustMatchActualStateAndNotAnAvailableAction() {
        val view=ScreenCompactor.compact(screen("옵션").copy(nodes=listOf(
            RawNode("r",-1,bounds=Bounds(0,0,1000,2400)),
            RawNode("r.0",0,text="옵션",className="Switch",clickable=true,checkable=true,checked=false,bounds=Bounds(0,100,1000,200)))))
        fun verdict(field:String,value:String)=com.google.gson.JsonParser.parseString(
            """{"ok":true,"proofs":[{"id":1,"field":"$field","value":"$value"}],"reason":"적용했어요"}""").asJsonObject
        assertNotNull(UiProofs.failure(verdict("checked","true"),"옵션을 켜줘",view,emptyList()))
        assertNull(UiProofs.failure(verdict("checked","false"),"옵션을 꺼줘",view,emptyList()))
        assertNotNull(UiProofs.failure(verdict("text","옵션"),"옵션을 켜줘",view,emptyList()))
        assertNotNull(UiProofs.failure(verdict("selected","true"),"옵션을 켜줘",view,emptyList()))
    }

    @Test fun completionReferencesAreConstrainedToTheObservedValuesAndChangedActions() {
        val view=ScreenCompactor.compact(screen("현재 선택 첫 안 16분","다른 안 22분",selected=true))
        val history=listOf(HistoryLine("click \"없는 메뉴\"","변화 없음"),HistoryLine("click \"확인\"","화면 바뀜"))
        val goal="두 후보의 차이를 알려주고 선택은 유지해줘"
        val grammar=UiProofs.grammar(goal,view,history)
        fun failure(ref:String):String? {
            val verdict=com.google.gson.JsonParser.parseString("""{"ok":true,"proofs":["$ref"],"reason":"확인"}""").asJsonObject
            return UiProofs.failure(verdict,goal,view,history)
        }
        assertNull(failure("s1"));assertNull(failure("s2"))
        assertNotNull(failure("s3"));assertNotNull(failure("h1"))
        assertNull(failure("h2"))
        val catalog=UiProofs.historyCatalog(goal,view,history)
        assertFalse("없는 메뉴" in catalog)
        assertTrue("사라짐" in catalog)
        assertFalse("6분" in grammar)
        val empty=UiProofs.grammar("자료를 실행해줘",ScreenCompactor.compact(screen("실행")),emptyList())
        assertFalse("true," in empty)
    }

    @Test fun aDetailTitleCannotProveExecutionOfAnUnperformedRequestedControl() {
        val snapshot=screen("자료 등록","등록").copy(nodes=listOf(
            RawNode("r",-1,bounds=Bounds(0,0,1000,2400)),
            RawNode("r.0",0,text="자료 등록",bounds=Bounds(0,100,1000,200)),
            RawNode("r.1",0,text="등록",clickable=true,className="Button",bounds=Bounds(0,300,1000,400))))
        val detail=ScreenCompactor.compact(snapshot)
        assertFalse(CompletionGrounding.hasOutcome("자료를 찾아서 등록해줘",detail,emptyList()))
        assertTrue(CompletionGrounding.hasOutcome("자료 등록 내용을 알려줘",detail,emptyList()))
        val applied=ScreenCompactor.compact(snapshot.copy(nodes=snapshot.nodes.take(2)+
            RawNode("r.2",0,text="자료 등록 완료",bounds=Bounds(0,500,1000,600))))
        assertTrue(CompletionGrounding.hasOutcome("자료를 찾아서 등록해줘",applied,
            listOf(HistoryLine("click \"등록\"","화면 바뀜"))))
    }

    @Test fun anActualAtomicTerminationStopsWithoutASecondPlannerOrFurtherActions() = runTest {
        var stopped=false;var actions=0
        val phone=object:Phone {
            override suspend fun observe()=screen(if(stopped) "작업 목록" else "끝내기")
            override suspend fun perform(view:ScreenView,action:AgentAction):Boolean { actions++;stopped=true;return true }
            override suspend fun openApp(name:String)=OpenAppResult(false,"")
            override suspend fun media(key:MediaKey)=false
            override fun now()=testScheduler.currentTime
        }
        val model=object:LanguageModel {
            override suspend fun decide(prompt:ModelPrompt,grammar:String):String {
                assertFalse("완료 확인" in prompt.user,"Do not send the already terminated task to another planner")
                return """{"action":"click","id":1,"check":false,"expect":"작업 종료"}"""
            }
        }
        assertEquals(Outcome.DONE,Agent(model,phone,book(),{emptyList()}).run("현재 작업을 종료해줘").outcome)
        assertEquals(1,actions)
    }

    @Test fun compoundRequestsAndRemainingTerminationControlsCannotUseTheAtomicPostcondition() {
        val before=ScreenCompactor.compact(screen("끝내기"))
        val after=ScreenCompactor.compact(screen("작업 목록"))
        val click=AgentAction.Click(1)
        assertTrue(CompletionGrounding.completedTermination("현재 작업을 종료해줘",click,before,after))
        assertFalse(CompletionGrounding.completedTermination("작업을 종료하고 이력을 보여줘",click,before,after))
        assertFalse(CompletionGrounding.completedTermination("다른 작업은 유지하고 현재 작업을 종료해줘",click,before,after))
        assertFalse(CompletionGrounding.completedTermination("10분 후에 현재 작업을 종료해줘",click,before,after))
        val pending=ScreenCompactor.compact(screen("끝내기","정말 끝낼까요?"))
        assertFalse(CompletionGrounding.completedTermination("현재 작업을 종료해줘",click,before,pending))
        val close=ScreenCompactor.compact(screen("메뉴 닫기"))
        assertFalse(CompletionGrounding.completedTermination("현재 작업을 종료해줘",click,close,after))
        fun active(label:String)=screen(label).copy(nodes=listOf(
            RawNode("r",-1,bounds=Bounds(0,0,1000,2400)),
            RawNode("r.0",0,text=label,clickable=true,className="Button",bounds=Bounds(0,100,1000,200)),
            RawNode("r.1",0,text="작업 진행 중, 남은 15분",bounds=Bounds(0,300,1000,400))))
        assertFalse(CompletionGrounding.completedTermination("현재 작업을 종료해줘",click,
            ScreenCompactor.compact(active("끝내기")),ScreenCompactor.compact(active("메뉴 열기"))))
    }
}
