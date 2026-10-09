package dev.localphone.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphone.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real selected model; only the phone UI is simulated. No recipes, places or task-specific tools. */
@RunWith(AndroidJUnit4::class)
class NativeOpenGoalTest {
    private data class Control(val label:String,val selected:Boolean=false,val checked:Boolean?=null,val textOnly:Boolean=false,val id:String="")
    private class Fixture(val appLabel:String="임의 앱",val controls:()->List<Control>,val click:(String)->Boolean):Phone {
        val dispatched=mutableListOf<String>()
        override suspend fun observe():Snapshot = Snapshot("example.unregistered",appLabel,
            listOf(RawNode("r",-1,bounds=Bounds(0,0,1000,2400))) + controls().mapIndexed { i,c ->
                RawNode("r.$i",0,text=c.label,viewId=c.id,clickable=!c.textOnly,checkable=c.checked!=null,
                    checked=c.checked==true,selected=c.selected,className=if(c.checked!=null) "Switch" else if(c.textOnly) "TextView" else "Button",
                    bounds=Bounds(0,100+i*180,1000,200+i*180))
            },1000,2400)
        override suspend fun perform(view:ScreenView,action:AgentAction):Boolean {
            if(action !is AgentAction.Click) return false
            val label=view.element(action.id)?.label.orEmpty()
            dispatched+=label
            return click(label)
        }
        override suspend fun openApp(name:String)=OpenAppResult(false,"현재 앱에서 요청 수행")
        override suspend fun media(key:MediaKey)=false
        override fun now()=android.os.SystemClock.elapsedRealtime()
    }
    private suspend fun run(goal:String,phone:Fixture):AgentResult {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.app
        var calls=0
        val model=object:LanguageModel {
            override suspend fun decide(prompt:ModelPrompt,grammar:String):String {
                calls++
                return app.llm.decide(prompt,grammar).also {
                    android.util.Log.i("AgentVerification","OPEN_GOAL_DECISION goal=$goal raw=$it")
                }
            }
        }
        assertNull(NavigationSession.forGoal(goal))
        val started=phone.now()
        val result=Agent(model,phone,RecipeBook({null},{}),{emptyList()},AgentConfig(maxSteps=9,withNote=app.prefs.withNote)).run(goal)
        android.util.Log.i("AgentVerification","OPEN_GOAL model=${app.llm.modelFile()?.name} goal=$goal outcome=${result.outcome} calls=$calls ms=${phone.now()-started}")
        assertEquals(result.toString(),Outcome.DONE,result.outcome)
        return result
    }

    @Test fun changesAnOptionWhileKeepingTheCurrentActivityRunning() = runBlocking {
        var phase="main"; var running=true; var avoided=false
        val phone=Fixture("길 안내 앱",{
            listOf(Control("경로안내 중",textOnly=true)) + when(phase) {
                "main" -> listOf(Control("",id="example:id/btn_drawer"),Control("안내 종료"))
                "menu" -> listOf(Control("경로 옵션"),Control("안내 종료"))
                else -> listOf(Control("유료도로 제외",checked=avoided),Control("다른 설정"))
            }
        },{label -> when {
            label.contains("메뉴·옵션") -> { phase="menu";true }
            label=="경로 옵션" -> { phase="options";true }
            label=="유료도로 제외" -> { avoided=!avoided;true }
            label=="안내 종료" -> { running=false;false }
            else -> false
        }})
        run("안내는 계속하면서 유료도로를 피하도록 바꿔줘",phone)
        assertTrue(avoided);assertTrue(running)
        assertFalse(phone.dispatched.contains("안내 종료"))
        Unit
    }

    @Test fun filtersThenRanksCandidatesAndAddsTheSelectedOneAsAnIntermediateStop() = runBlocking {
        var phase="results";var chosen="";var via=false
        val phone=Fixture("지도 앱",{
            when(phase) {
                "results" -> listOf(Control("A 주유소 0.4 km 영업 종료"),Control("B 주유소 1.2 km 영업 중"),Control("C 주유소 2.1 km 영업 중"))
                "detail" -> listOf(Control("$chosen 주유소",textOnly=true),Control("목적지"),Control("경유지"))
                else -> listOf(Control("경로안내 중",textOnly=true),Control("경유지 $chosen 주유소",textOnly=true),Control("목적지 회사",textOnly=true))
            }
        },{label -> when {
            phase=="results" -> { chosen=label.take(1);phase="detail";true }
            label=="경유지" -> { via=true;phase="route";true }
            else -> false
        }})
        run("문 연 곳 중 거리가 가장 짧은 곳을 골라 경유지로 넣어줘",phone)
        assertEquals("B",chosen);assertTrue(via)
        Unit
    }

    @Test fun changesAnUnknownAppsPreferenceWithoutARegisteredCommand() = runBlocking {
        var phase="main";var large=false
        val phone=Fixture("메모 앱",{
            when(phase) {
                "main" -> listOf(Control("메모 목록",textOnly=true),Control("",id="example:id/btn_options"))
                "menu" -> listOf(Control("표시 옵션"),Control("정렬 방식"))
                else -> listOf(Control("글자 크기",textOnly=true),Control("작게",selected=!large),Control("크게",selected=large))
            }
        },{label -> when {
            label.contains("메뉴·옵션") -> { phase="menu";true }
            label=="표시 옵션" -> { phase="options";true }
            label=="크게" -> { large=true;true }
            else -> false
        }})
        run("글자 크기를 크게 바꿔줘",phone)
        assertTrue(large)
        Unit
    }

    @Test fun readsAComparisonAndPreservesTheCurrentSelection() = runBlocking {
        var operations=0
        val phone=Fixture("후보 비교 앱",{
            listOf(Control("현재 선택 첫 안 16분",selected=true),Control("다른 안 22분"))
        },{operations++;false})
        val result=run("두 후보의 시간 차이를 알려주고 현재 선택은 그대로 둬",phone)
        assertEquals(0,operations)
        assertTrue(result.say,result.say.contains("6")||result.say.contains("여섯"))
        Unit
    }
}
