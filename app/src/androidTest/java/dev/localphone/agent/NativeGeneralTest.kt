package dev.localphone.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphone.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real selected model, an unregistered arbitrary app and live-derived menu affordances. */
@RunWith(AndroidJUnit4::class)
class NativeGeneralTest {
    @Test fun navigationStopWordingsFinishWithoutExploringTheNormalMapAfterExit() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.app
        for (goal in listOf("네비 꺼줘", "네비 종료해줘")) {
            var phase = "guidance"
            var calls = 0
            var attemptedAfterExit = false
            fun snapshot(): Snapshot {
                fun node(i: Int, label: String, id: String = "", selected: Boolean = false, button: Boolean = true) = RawNode("r.$i", 0,
                    text = label, viewId = id, clickable = button, selected = selected,
                    className = if (button) "Button" else "TextView", bounds = Bounds(0,100+i*180,1000,200+i*180))
                val nodes = if (phase == "normal") listOf(node(0, "네이버지도 검색"), node(1, "길찾기"),
                    node(2, "음식점"), node(3, "카페"), node(4, "발견 탭 발견", selected = true), node(5, "내비게이션 탭 내비게이션"))
                else buildList {
                    add(node(0, "지도", button = false)); add(node(1, "49 m", button = false))
                    add(node(2, "검색")); add(node(3, "주유소")); add(node(4, "Spotify"))
                    add(node(5, "", "example:id/v_reroute")); add(node(6, "", "example:id/v_drawer"))
                    add(node(7, "5.1 km", button = false))
                    if (phase == "menu") { add(node(8, "지도설정")); add(node(9, "주행설정")); add(node(10, "안내 종료")) }
                }
                return Snapshot("com.nhn.android.nmap", "네이버지도", listOf(RawNode("r",-1,bounds=Bounds(0,0,1000,2400)))+nodes,1000,2400)
            }
            val phone = object : Phone {
                override suspend fun observe() = snapshot()
                override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
                    if(phase=="normal") attemptedAfterExit=true
                    val label = (action as? AgentAction.Click)?.let { view.element(it.id)?.label }.orEmpty()
                    if (label.contains("메뉴·옵션")) phase = if (phase == "menu") "guidance" else "menu"
                    else if (label == "안내 종료") phase = "normal"
                    else return false
                    return true
                }
                override suspend fun openApp(name: String) = OpenAppResult(false,"이미 요청한 앱 화면")
                override suspend fun media(key: MediaKey) = false
                override fun now() = android.os.SystemClock.elapsedRealtime()
            }
            val model = object : LanguageModel {
                override suspend fun decide(prompt: ModelPrompt, grammar: String): String {
                    calls++
                    return app.llm.decide(prompt,grammar).also {
                        android.util.Log.i("AgentVerification", "NAV_STOP_DECISION wording=$goal phase=$phase raw=$it")
                    }
                }
            }
            val result = Agent(model,phone,RecipeBook({null}, {}),{emptyList()},AgentConfig(maxSteps=6,withNote=app.prefs.withNote))
                .run(goal)
            assertEquals(result.toString(),Outcome.DONE,result.outcome)
            assertEquals("normal",phase)
            assertFalse("A completed task must not cause further map actions",attemptedAfterExit)
            assertTrue(result.history.any { it.action.contains("안내 종료") })
            android.util.Log.i("AgentVerification", "NAV_STOP wording=$goal phase=$phase calls=$calls normalMapVerified=true")
        }
        Unit
    }
    @Test fun searchThenPlaybackCompletesBothPartsWithTheRealModel() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.app
        var phase = "home"
        var query = ""
        var calls = 0
        var attemptedPause = false
        fun snapshot(): Snapshot {
            fun node(i: Int, label: String, editable: Boolean = false, button: Boolean = true) = RawNode("r.$i", 0,
                text = label, hint = if (editable) "검색" else "", editable = editable, clickable = button,
                className = if (editable) "EditText" else if (button) "Button" else "TextView",
                bounds = Bounds(0, 100 + i * 200, 1000, 200 + i * 200))
            val nodes = when (phase) {
                "home" -> listOf(node(0, "", editable = true), node(1, "홈", button = false))
                "results" -> listOf(node(0, "고양이 첫 영상"), node(1, "고양이 두 번째 영상"), node(2, "고양이 검색 결과", button = false))
                "detail" -> listOf(node(0, "재생"), node(1, "고양이 첫 영상", button = false))
                else -> listOf(node(0, "일시 정지"), node(1, "고양이 첫 영상 재생 중", button = false))
            }
            return Snapshot("example.player", "임의플레이어", listOf(RawNode("r", -1, bounds = Bounds(0,0,1000,2000)))+nodes, 1000, 2000)
        }
        val phone = object : Phone {
            override suspend fun observe() = snapshot()
            override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
                when (action) {
                    is AgentAction.Type -> { query = action.text; phase = "results" }
                    is AgentAction.Click -> {
                        val label = view.element(action.id)!!.label
                        if(phase=="playing" && label=="일시 정지") attemptedPause=true
                        if (phase == "results" && label == "고양이 첫 영상") phase = "detail"
                        else if (phase == "detail" && label == "재생") phase = "playing"
                    }
                    else -> return false
                }
                return true
            }
            override suspend fun openApp(name: String) = OpenAppResult(false, "없음")
            override suspend fun media(key: MediaKey) = false
            override fun now() = android.os.SystemClock.elapsedRealtime()
        }
        val model = object : LanguageModel {
            override suspend fun decide(prompt: ModelPrompt, grammar: String): String {
                calls++
                return app.llm.decide(prompt, grammar).also {
                    val verification = prompt.user.contains("완료 확인")
                    android.util.Log.i("AgentVerification", "COMPOUND_DECISION phase=$phase verification=$verification raw=$it")
                }
            }
        }
        val result = Agent(model, phone, RecipeBook({null}, {}), {emptyList()}, AgentConfig(maxSteps = 8, withNote = false))
            .run("임의플레이어에서 고양이 검색하고 첫 결과를 재생해줘")
        assertEquals(result.toString(), Outcome.DONE, result.outcome)
        assertEquals("고양이", query)
        assertEquals("playing", phase)
        assertFalse("A completed playback goal must not be undone",attemptedPause)
        assertTrue(calls > 0)
        android.util.Log.i("AgentVerification", "NATIVE_COMPOUND model=${app.llm.modelFile()?.name} phase=$phase calls=$calls steps=${result.history.size}")
        Unit
    }
    @Test fun stopsAnUnknownAppTaskThroughItsMenuWithoutADomainRecipe() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.app
        var phase = "running"
        val dispatched = mutableListOf<String>()
        fun snapshot(): Snapshot {
            fun node(i: Int, label: String, id: String = "", button: Boolean = true) = RawNode("r.$i", 0,
                text = label, viewId = id, clickable = button, className = if (button) "Button" else "TextView",
                bounds = Bounds(0, 100 + i * 200, 1000, 200 + i * 200))
            val nodes = when (phase) {
                "running" -> listOf(node(0, "", "example:id/btn_refresh"), node(1, "", "example:id/btn_drawer"), node(2, "작업 진행 중", button = false))
                "menu" -> listOf(node(0, "다시 시작"), node(1, "끝내기"))
                else -> listOf(node(0, "작업이 종료되었습니다", button = false))
            }
            return Snapshot("example.unregistered", "임의 작업 앱", listOf(RawNode("r", -1, bounds = Bounds(0,0,1000,2000)))+nodes, 1000, 2000)
        }
        val phone = object : Phone {
            override suspend fun observe() = snapshot()
            override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
                if (action is AgentAction.Click) {
                    val label = view.element(action.id)!!.label; dispatched += label
                    if (label.contains("메뉴·옵션")) phase = "menu"
                    if (label == "끝내기") phase = "stopped"
                }
                return true
            }
            override suspend fun openApp(name: String) = OpenAppResult(false, "없음")
            override suspend fun media(key: MediaKey) = false
            override fun now() = android.os.SystemClock.elapsedRealtime()
        }
        val result = Agent(app.llm, phone, RecipeBook({null}, {}), {emptyList()}, AgentConfig(maxSteps = 6, withNote = false))
            .run("지금 실행 중인 작업을 끝내줘")
        assertEquals(result.toString(), Outcome.DONE, result.outcome)
        assertEquals("stopped", phase)
        assertTrue(dispatched.any { it.contains("메뉴·옵션") })
        assertTrue(dispatched.contains("끝내기"))
        android.util.Log.i("AgentVerification", "NATIVE_GENERAL model=${app.llm.modelFile()?.name} phase=$phase steps=${result.history.size}")
        Unit
    }
}
