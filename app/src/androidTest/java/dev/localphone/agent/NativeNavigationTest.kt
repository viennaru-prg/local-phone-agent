package dev.localphone.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphone.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real selected on-device model, simulated app screens: no real route or media key is dispatched. */
@RunWith(AndroidJUnit4::class)
class NativeNavigationTest {
    @Test fun unregisteredOfficeUsesScreenActionsWithTheRealModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.app
        var state = "places"
        var mediaCalls = 0
        fun view(): Snapshot {
            val labels = when (state) {
                "places" -> listOf("집", "회사 주소로 길찾기", "직장 목적지 선택", "자주 가는 곳")
                "route" -> listOf("회사", "자동차 32분", "안내 시작")
                else -> listOf("안내 종료", "남은 거리 10km")
            }
            val root = RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000))
            return Snapshot(NavigationSession.NAVER_MAP, "네이버 지도", listOf(root) + labels.mapIndexed { i, s ->
                RawNode("r.$i", 0, text = s, clickable = state != "driving", className = "Button", bounds = Bounds(0, 100 + i * 200, 1000, 200 + i * 200))
            }, 1000, 2000)
        }
        val phone = object : Phone {
            override suspend fun observe() = view()
            override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
                if (action is AgentAction.Click) when (view.element(action.id)?.label) {
                    "회사 주소로 길찾기", "직장 목적지 선택" -> state = "route"
                    "안내 시작" -> state = "driving"
                }
                return true
            }
            override suspend fun openApp(name: String) = OpenAppResult(true, "열림")
            override suspend fun media(key: MediaKey): Boolean { mediaCalls++; return true }
            override fun now() = android.os.SystemClock.elapsedRealtime()
        }
        val tools = object : Tools {
            override suspend fun navigate(place: Place): String? = error("no registered places")
            override suspend fun media(key: MediaKey): String? { mediaCalls++; return "wrong" }
            override suspend fun prepareNavigation() = true
        }
        val goal = "회사로 안내해 줘"
        val records = mutableListOf<StepRecord>()
        val listener = object : AgentListener { override fun step(record: StepRecord) { records += record } }
        val agent = Agent(app.llm, phone, RecipeBook({ null }, {}), { emptyList() },
            AgentConfig(maxSteps = 6, withNote = false), listener, NavigationSession.forGoal(goal))
        val result = Assistant(app.llm, tools, PlaceBook({ null }, {}), agent, listener).run(goal)
        assertEquals(result.toString(), Outcome.DONE, result.outcome)
        assertEquals("driving", state)
        assertEquals(0, mediaCalls)
        assertTrue(records.first().action.contains("Screen"))
        assertTrue(records.none { it.source == "router" && it.raw.isNotBlank() })
        assertTrue("actual model was never called", records.any { it.source == "model" && it.raw.isNotBlank() })
        android.util.Log.i("AgentVerification", "NATIVE_NAV model=${app.llm.modelFile()?.name} steps=${records.size} lastStats=${app.llm.lastStats}")
        Unit
    }
}
