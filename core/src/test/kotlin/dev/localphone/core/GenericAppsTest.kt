package dev.localphone.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Music apps with no profile: the generic skills must discover how they work.
 * [selectFirst] = a tap only selects a row (like ClipStream); otherwise a tap plays it.
 * Rows carry their own "삭제" button (unlike ClipStream's icon without a node).
 */
private class OtherPlayer(private val scope: TestScope, private val selectFirst: Boolean) : Phone {
    val playlist = mutableListOf("Woody - 어제보다 슬픈 오늘", "더 크로스 - 당신을 위하여", "야다 - 이미 슬픈 사랑")
    var selected = ""
    var nowPlaying = ""
    var playing = false
    val pkg = if (selectFirst) "com.example.selectfirst" else "com.example.tapplays"
    override suspend fun observe(): Snapshot {
        val n = mutableListOf(RawNode("r", -1, bounds = Bounds(0, 0, 1080, 2340)))
        playlist.forEachIndexed { i, t ->
            val top = 300 + i * 200
            n += RawNode("r.$i", 0, text = t, clickable = true, className = "android.widget.Button", selected = t == selected, bounds = Bounds(40, top, 1040, top + 160))
            n += RawNode("r.$i.d", 0, desc = "삭제", clickable = true, className = "android.widget.ImageButton", bounds = Bounds(900, top + 30, 1020, top + 130))
        }
        n += RawNode("b.t", 0, text = nowPlaying, bounds = Bounds(40, 2060, 600, 2120))
        n += RawNode("b.p", 0, desc = "이전 곡", clickable = true, className = "android.widget.Button", bounds = Bounds(620, 2050, 720, 2170))
        n += RawNode("b.x", 0, desc = if (playing) "일시정지" else "재생", clickable = true, className = "android.widget.Button", bounds = Bounds(730, 2050, 860, 2170))
        n += RawNode("b.n", 0, desc = "다음 곡", clickable = true, className = "android.widget.Button", bounds = Bounds(870, 2050, 980, 2170))
        return Snapshot(pkg, "Other Player", n, 1080, 2340)
    }
    private fun play(label: String) { nowPlaying = label; playing = true }
    override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
        val label = when (action) {
            is AgentAction.Click -> view.element(action.id)?.label
            is AgentAction.DoubleTap -> view.element(action.id)?.label
            else -> null
        }.orEmpty()
        when {
            action is AgentAction.DoubleTap && label in playlist -> play(label)
            action is AgentAction.Click && label in playlist -> if (selectFirst && selected != label) selected = label else play(label)
            action is AgentAction.Click && label.startsWith("삭제 · ") -> playlist.remove(label.substringAfter(" · "))
            action is AgentAction.TapEnd -> error("this app has real delete buttons; the row end is not one")
        }
        return true
    }
    override suspend fun openApp(name: String) = OpenAppResult(true, "이미 열려 있음")
    override suspend fun media(key: MediaKey) = false
    override fun musicActive() = playing
    override fun now() = scope.testScheduler.currentTime
}

class GenericAppsTest {
    @BeforeTest fun forget() = AppProfiles.restore(emptyMap())

    private val noModel = object : LanguageModel {
        override suspend fun decide(prompt: ModelPrompt, grammar: String) = """{"action":"back","check":false}"""
    }

    private fun agent(phone: Phone) = Agent(noModel, phone, RecipeBook({ null }, {}), { emptyList() }, AgentConfig(withNote = false))

    @Test fun anAppWhereATapPlaysIsPlayedWithOneTapAndThatIsLearned() = runTest {
        val phone = OtherPlayer(this, selectFirst = false)
        val result = agent(phone).run("더 크로스 틀어줘")
        val log = result.history.joinToString("\n")
        assertEquals(Outcome.DONE, result.outcome, log)
        assertEquals("더 크로스 - 당신을 위하여", phone.nowPlaying, log)
        assertTrue(result.history.none { it.action.startsWith("double_tap") }, log)
        assertEquals("tap", AppProfiles.forPackage(phone.pkg).rowPlay)
    }

    @Test fun anAppWhereATapOnlySelectsEscalatesToTwoTapsAndRemembersIt() = runTest {
        val phone = OtherPlayer(this, selectFirst = true)
        val first = agent(phone).run("야다 이미 슬픈 사랑 틀어줘")
        assertEquals(Outcome.DONE, first.outcome, first.history.joinToString("\n"))
        assertEquals("double_tap", AppProfiles.forPackage(phone.pkg).rowPlay)
        // Next command: straight to two taps, no single tap first.
        phone.playing = false; phone.selected = ""
        val second = agent(phone).run("더 크로스 틀어줘")
        val log = second.history.joinToString("\n")
        assertEquals(Outcome.DONE, second.outcome, log)
        assertTrue(second.history.none { it.action.startsWith("click \"더 크로스") }, log)
    }

    @Test fun aRowsOwnDeleteButtonIsUsedAndTheRowMustDisappear() = runTest {
        val phone = OtherPlayer(this, selectFirst = false)
        val result = agent(phone).run("더 크로스 재생목록에서 빼줘")
        val log = result.history.joinToString("\n")
        assertEquals(Outcome.DONE, result.outcome, log)
        assertEquals(listOf("Woody - 어제보다 슬픈 오늘", "야다 - 이미 슬픈 사랑"), phone.playlist, log)
        assertEquals("button", AppProfiles.forPackage(phone.pkg).rowDelete)
    }

    @Test fun profilesComeFromData() {
        val clip = AppProfiles.forPackage("local.clipstream.player")
        assertEquals("double_tap", clip.rowPlay)
        assertEquals("trailing_icon", clip.rowDelete)
        assertEquals("com.nhn.android.nmap", AppProfiles.forRole("navigation")?.packageName)
        assertEquals("클립스트림", AppProfiles.appFor("music"))
        assertEquals(null, AppProfiles.forPackage("com.unknown").rowPlay)
    }
}
