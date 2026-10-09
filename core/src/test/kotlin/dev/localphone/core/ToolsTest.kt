package dev.localphone.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ToolsTest {
    @Test fun navigationCannotBecomePauseWhenNoLocalPlacesExist() {
        val b = book()
        for (goal in listOf("회사로 안내해 줘", "회사로 가자", "집으로 안내해줘", "학교까지 안내해줘")) {
            assertTrue(Router.isNavigationGoal(goal), goal)
            assertEquals(Route.Screen, Router.validate(goal, Route.Media(MediaKey.PAUSE), b))
            assertFalse(Router.allowsMedia(goal))
        }
    }
    @Test fun everydayMediaPhrasesUseTheFastPath() {
        val cases = mapOf(
            "노래 꺼줘" to MediaKey.PAUSE, "음악 좀 멈춰" to MediaKey.PAUSE, "음악 그만" to MediaKey.PAUSE, "일시정지" to MediaKey.PAUSE,
            "다음 노래" to MediaKey.NEXT, "노래 넘겨줘" to MediaKey.NEXT, "다음 곡 틀어줘" to MediaKey.NEXT,
            "이전 곡" to MediaKey.PREVIOUS, "앞 노래 틀어줘" to MediaKey.PREVIOUS,
            "노래 다시 틀어줘" to MediaKey.PLAY, "음악 켜줘" to MediaKey.PLAY, "음악 재생해줘" to MediaKey.PLAY)
        for ((goal, key) in cases) assertEquals(key, Router.simpleMediaKey(goal), goal)
        for (goal in listOf("아이유 노래 틀어줘", "유튜브에서 아이유 노래 검색해줘", "재생목록에 추가해줘", "다음 주 일정 알려줘"))
            assertNull(Router.simpleMediaKey(goal), goal)
    }

    @Test fun ordinaryMediaControlsRemainAllowedButSongSearchDoesNot() {
        assertTrue(Router.allowsMedia("음악 일시정지해줘"))
        assertTrue(Router.allowsMedia("다음 곡으로 넘겨줘"))
        assertFalse(Router.allowsMedia("유튜브에서 아이유 노래 검색해줘"))
        assertFalse(Router.allowsMedia("내비 안내 종료해줘"))
    }
    @Test fun mapLaunchDoesNotRequireAPlace() {
        assertNull(Router.quick("지도 켜줘", book()))
        assertFalse(Router.mightUseTool("지도 켜줘"))
        assertFalse(Router.isNavigationGoal("내비 켜줘"))
        assertFalse(Router.isNavigationGoal("네이버 지도에서 길찾기 화면 열어줘"))
        assertNull(NavigationSession.forGoal("네이버 지도에서 길찾기 화면 열어줘"))
        assertNull(Router.quick("카카오맵에서 회사로 안내해줘", book(work)))
    }
    @Test fun screenAgentAlsoRejectsAnUnrelatedMediaAction() {
        val view = ScreenView(Snapshot(NavigationSession.NAVER_MAP, "네이버 지도", emptyList(), 1000, 2000), emptyList())
        assertNotNull(Guard.blocked("회사로 안내해줘", AgentAction.Media(MediaKey.PAUSE), view))
    }
    private fun book(vararg places: Place): PlaceBook {
        val store = arrayOf<String?>(null)
        return PlaceBook({ store[0] }, { store[0] = it }).also { b -> places.forEach(b::put) }
    }
    private val work = Place("회사", listOf("직장"), "서울특별시 중구 세종대로 110", 37.25, 126.98)
    private val home = Place("집", emptyList(), "경기 화성시", 37.2, 126.9)

    @Test fun obviousNavigationNeedsNoModel() {
        val b = book(work, home)
        assertEquals(Route.Navigate(work), Router.quick("회사로 안내해줘", b))
        assertEquals(Route.Navigate(work), Router.quick("직장 가자", b))
        assertEquals(Route.Navigate(home), Router.quick("집으로 가는 길 알려줘", b))
        assertNull(Router.quick("길안내 종료해줘", b))
        assertNull(Router.quick("회사 전화번호 알려줘", b))
        assertNull(Router.quick("학교로 가자", b), "unregistered place goes to the model/screen")
    }

    @Test fun grammarOffersOnlyRegisteredPlaces() {
        val g = Router.grammar(listOf(work, home))
        assertTrue("\"\\\"회사\\\"\"" in g, g)
        assertTrue("\"\\\"직장\\\"\"" in g, g)
        assertTrue("navigate" !in Router.grammar(emptyList()))
    }

    @Test fun parseMapsModelOutputToRoutes() {
        val b = book(work)
        assertEquals(Route.Navigate(work), Router.parse("""{"tool":"navigate","place":"직장"}""", b))
        assertEquals(Route.Media(MediaKey.NEXT), Router.parse("""{"tool":"media","key":"next"}""", b))
        assertEquals(Route.Screen, Router.parse("""{"tool":"screen"}""", b))
        assertEquals(Route.Screen, Router.parse("garbage", b))
    }

    @Test fun assistantUsesTheDeepLinkForARegisteredPlace() = runTest {
        val calls = mutableListOf<String>()
        val tools = object : Tools {
            override suspend fun navigate(place: Place) = "${place.name}로 안내를 시작할게요".also { calls += "nav ${place.name}" }
            override suspend fun media(key: MediaKey) = null
        }
        val noModel = object : LanguageModel { override suspend fun decide(prompt: ModelPrompt, grammar: String): String = error("model not needed") }
        val phone = object : Phone {
            override suspend fun observe(): Snapshot? = error("screen not needed")
            override suspend fun perform(view: ScreenView, action: AgentAction) = false
            override suspend fun openApp(name: String) = OpenAppResult(false, "")
            override suspend fun media(key: MediaKey) = false
            override fun now() = 0L
        }
        val agent = Agent(noModel, phone, RecipeBook({ null }, {}), { emptyList() })
        val result = Assistant(noModel, tools, book(work), agent).run("회사로 안내해줘")
        assertEquals(Outcome.DONE, result.outcome)
        assertEquals(listOf("nav 회사"), calls)
    }
}
