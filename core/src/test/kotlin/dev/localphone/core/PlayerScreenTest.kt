package dev.localphone.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A player in Secure Folder: media keys never reach it, only its own buttons work. */
private class FakePlayer(private val scope: TestScope) : Phone {
    var playing = false
    var dialog = false
    private fun snap(vararg items: String) = Snapshot("local.clipstream.player", "Clipstream Player",
        listOf(RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000))) + items.mapIndexed { i, t ->
            RawNode("r.$i", 0, desc = t, clickable = true, className = "android.widget.Button", bounds = Bounds(0, 300 + i * 100, 1000, 380 + i * 100))
        }, 1000, 2000)
    override suspend fun observe() = when {
        dialog -> snap("새 재생목록 이름", "취소", "확인")
        else -> snap("재생목록 만들기", "이전 곡", if (playing) "일시정지" else "재생", "다음 곡")
    }
    override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
        when ((action as? AgentAction.Click)?.let { view.element(it.id)?.label }) {
            "재생" -> playing = true
            "일시정지" -> playing = false
            "재생목록 만들기" -> dialog = true
        }
        return true
    }
    override suspend fun openApp(name: String) = OpenAppResult(true, "이미 열려 있음")
    override suspend fun media(key: MediaKey) = true
    override fun musicActive() = playing
    override fun now() = scope.testScheduler.currentTime
}

class PlayerScreenTest {
    private fun stubbornModel(calls: MutableList<String>) = object : LanguageModel {
        override suspend fun decide(prompt: ModelPrompt, grammar: String): String {
            calls += prompt.system
            if (prompt.system == Prompts.VERIFY_SYSTEM) return """{"ok":false,"proofs":[],"reason":"모름"}"""
            // The failure seen on the phone: the small model kept choosing "재생목록 만들기".
            val id = Regex("\\[(\\d+)] \\S+ \"재생목록 만들기").find(prompt.user)?.groupValues?.get(1)
            return if (id != null) """{"action":"click","id":$id,"check":false}""" else """{"action":"back","check":false}"""
        }
    }

    @Test fun playPressesThePlayersOwnButtonWithoutAModel() = runTest {
        val phone = FakePlayer(this)
        val calls = mutableListOf<String>()
        val result = Agent(stubbornModel(calls), phone, RecipeBook({ null }, {}), { emptyList() }, AgentConfig(withNote = false)).run("음악 재생해줘")
        val log = result.history.joinToString("\n")
        assertEquals(Outcome.DONE, result.outcome, log)
        assertTrue(phone.playing, log)
        assertFalse(phone.dialog, log)
        assertTrue(calls.isEmpty(), "no model call needed: $calls")
    }

    @Test fun pauseWhenNothingPlaysSaysSo() = runTest {
        val phone = FakePlayer(this)
        val result = Agent(stubbornModel(mutableListOf()), phone, RecipeBook({ null }, {}), { emptyList() }, AgentConfig(withNote = false)).run("음악 멈춰")
        assertEquals(Outcome.DONE, result.outcome)
        assertEquals("지금 재생 중인 음악이 없어요.", result.say)
    }

    @Test fun creatingAPlaylistIsNotPartOfPlaying() {
        val view = ScreenCompactor.compact(Snapshot("p", "P", listOf(RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000)),
            RawNode("r.0", 0, desc = "재생목록 만들기", clickable = true, bounds = Bounds(0, 300, 1000, 380))), 1000, 2000))
        val id = view.elements.first { it.label == "재생목록 만들기" }.id
        assertTrue(Guard.blocked("음악 재생해줘", AgentAction.Click(id), view) != null)
        assertTrue(Guard.blocked("재생목록 만들어줘", AgentAction.Click(id), view) == null)
        val banner = ScreenCompactor.compact(Snapshot("p", "P", listOf(RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000)),
            RawNode("r.0", 0, text = "새 버전 1.1.108을 설치할 수 있습니다.", clickable = true, bounds = Bounds(0, 300, 1000, 380))), 1000, 2000))
        assertTrue(Guard.blocked("음악 재생해줘", AgentAction.Click(banner.elements.first().id), banner) != null)
    }
}

class SoundsLikeTest {
    @Test fun hangulAndLatinNamesMatch() {
        assertTrue(GoalText.soundsLike("클립스트림", "Clipstream Player"))
        assertTrue(GoalText.soundsLike("클립 스트림", "Clipstream Player"))
        assertTrue(GoalText.soundsLike("스포티파이", "Spotify"))
        assertFalse(GoalText.soundsLike("클립스트림", "Chrome"))
        assertFalse(GoalText.soundsLike("유튜브", "YouTube Music"))
    }
}
