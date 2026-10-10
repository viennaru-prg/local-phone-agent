package dev.localphone.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppCloseTest {
    @Test fun closeCommandsNameTheAppOrTheOneInFront() {
        assertEquals("", AppClose.target("종료해 줘"))
        assertEquals("", AppClose.target("꺼줘"))
        assertEquals("", AppClose.target("이 앱 닫아줘"))
        assertEquals("네이버지도", AppClose.target("네이버지도 종료해 줘"))
        assertEquals("네이버 지도", AppClose.target("네이버 지도 앱 꺼줘"))
        // Guidance and music keep their own meaning.
        assertNull(AppClose.target("안내 종료해 줘"))
        assertNull(AppClose.target("내비게이션 꺼줘"))
        assertNull(AppClose.target("음악 꺼줘"))
        assertNull(AppClose.target("회사로 안내해줘"))
    }

    @Test fun theModelReadsOddPhrasingAsClosing() {
        val json = "{\"intent\":\"close_app\",\"target\":\"\",\"app\":\"네이버 지도\"}"
        assertEquals("네이버 지도 종료해줘", Intents.rewrite("네이버 지도 이제 닫아줄래", json))
        assertEquals("종료해줘", Intents.rewrite("이제 그만 닫을래", "{\"intent\":\"close_app\",\"target\":\"\",\"app\":\"\"}"))
    }

    private class Recorder(var guiding: Boolean) : Tools {
        val closed = mutableListOf<String?>()
        override suspend fun navigate(place: Place): String? = null
        override suspend fun media(key: MediaKey): String? = null
        override suspend fun closeApp(name: String?, guidanceChecked: Boolean): CloseResult {
            if (guiding && !guidanceChecked) return CloseResult.NavigationApp
            closed += name
            return if (name == "와이파이") CloseResult.NotAnApp else CloseResult.Closed("${name ?: "네이버 지도"}를 닫았어요.")
        }
    }

    private val noModel = object : LanguageModel { override suspend fun decide(prompt: ModelPrompt, grammar: String): String = error("model not needed") }
    private val phone = object : Phone {
        override suspend fun observe(): Snapshot? = error("screen not needed")
        override suspend fun perform(view: ScreenView, action: AgentAction) = false
        override suspend fun openApp(name: String) = OpenAppResult(false, "")
        override suspend fun media(key: MediaKey) = false
        override fun now() = 0L
    }

    @Test fun notGuidingTheMapIsClosed() = runTest {
        val tools = Recorder(guiding = false)
        val agent = Agent(noModel, phone, RecipeBook({ null }, {}), { emptyList() })
        val result = Assistant(noModel, tools, PlaceBook({ null }, {}), agent).run("종료해 줘")
        assertEquals(Outcome.DONE, result.outcome)
        assertEquals("네이버 지도를 닫았어요.", result.say)
        assertEquals(listOf<String?>(null), tools.closed)
    }

    @Test fun duringGuidanceTheBareCommandEndsTheDriveOnly() = runTest {
        // The end-guidance run needs a screen; here only the decision is checked: the map is not closed
        // before guidance was checked.
        val tools = Recorder(guiding = true)
        assertEquals(CloseResult.NavigationApp, tools.closeApp(null))
        assertEquals(CloseResult.Closed("네이버 지도를 닫았어요."), tools.closeApp(null, guidanceChecked = true))
    }
}
