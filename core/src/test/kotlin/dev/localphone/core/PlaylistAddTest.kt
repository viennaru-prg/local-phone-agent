package dev.localphone.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ClipStream's main screen: search box (enter does nothing, its 검색 button searches), results with "+", playlist. */
private class FakeClipStream(private val scope: TestScope) : Phone {
    var query = ""
    var results = false
    val playlist = mutableListOf("Woody - 어제보다 슬픈 오늘", "더 크로스 - 당신을 위하여")
    var created = 0
    private val rows = listOf("IU 좋은 날 라이브 무대", "아이유(IU) - 좋은 날 [가사/Lyrics]", "Good day (inst)")
    override suspend fun observe(): Snapshot {
        val n = mutableListOf(
            RawNode("r", -1, bounds = Bounds(0, 0, 1080, 2340)),
            RawNode("r.0", 0, clickable = true, bounds = Bounds(0, 0, 1080, 2000)),
            RawNode("r.0.0", 1, text = query, hint = "노래, 아티스트, 링크 검색", editable = true, className = "android.widget.EditText", bounds = Bounds(50, 260, 750, 370)),
            RawNode("r.0.1", 1, desc = "검색", clickable = true, className = "android.widget.Button", enabled = query.isNotEmpty(), bounds = Bounds(760, 260, 860, 370)),
            RawNode("r.0.2", 1, scrollable = true, bounds = Bounds(0, 450, 1080, 1000)),
            RawNode("r.0.3", 1, desc = "재생목록 만들기", clickable = true, className = "android.widget.Button", bounds = Bounds(600, 1050, 700, 1150)),
            RawNode("r.0.4", 1, text = "기본 재생목록 ${playlist.size}", clickable = true, className = "android.widget.Button", bounds = Bounds(100, 1180, 600, 1260)),
        )
        if (results) rows.forEachIndexed { i, t ->
            val top = 480 + i * 170
            n += RawNode("r.0.2.$i", 4, text = t, bounds = Bounds(200, top, 800, top + 60))
            n += RawNode("r.0.2.$i.b", 4, desc = "현재 재생목록에 추가", clickable = true, className = "android.widget.Button", bounds = Bounds(880, top - 20, 1000, top + 100))
        }
        playlist.forEachIndexed { i, t -> n += RawNode("r.0.5.$i", 1, text = t, clickable = true, className = "android.widget.Button", bounds = Bounds(100, 1300 + i * 150, 900, 1420 + i * 150)) }
        return Snapshot("local.clipstream.player", "Clipstream Player", n, 1080, 2340)
    }
    override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
        when (action) {
            is AgentAction.Type -> { query = action.text; results = false }
            is AgentAction.Click -> {
                val label = view.element(action.id)?.label.orEmpty()
                when {
                    label == "검색" -> results = true
                    label.startsWith("현재 재생목록에 추가 · ") -> playlist += label.substringAfter(" · ")
                    label == "재생목록 만들기" -> created++
                }
            }
            else -> {}
        }
        return true
    }
    override suspend fun openApp(name: String) = OpenAppResult(true, "이미 열려 있음")
    override suspend fun media(key: MediaKey) = false
    override fun now() = scope.testScheduler.currentTime
}

class PlaylistAddTest {
    @Test fun goalsNameTheSong() {
        assertEquals("아이유 좋은날", GoalText.playlistAdd("클립스트림에서 아이유 좋은날 재생목록에 추가해줘"))
        assertEquals("아이유 좋은날", GoalText.playlistAdd("아이유 좋은날 추가해줘"))
        assertEquals("좋은날", GoalText.playlistAdd("좋은날 노래 플레이리스트에 넣어줘"))
        assertEquals("아이유 좋은날", GoalText.playlistAdd("아이유 좋은날을 재생목록에 담아줘"))
        assertEquals(null, GoalText.playlistAdd("회사로 안내해줘"))
    }

    @Test fun searchesPressesSearchAndAddsTheMatchingRowWithoutTheModel() = runTest {
        val phone = FakeClipStream(this)
        val calls = mutableListOf<String>()
        val model = object : LanguageModel {
            override suspend fun decide(prompt: ModelPrompt, grammar: String): String {
                calls += prompt.system
                if (prompt.system == Prompts.VERIFY_SYSTEM) return """{"ok":false,"proofs":[],"reason":"모름"}"""
                val id = Regex("\\[(\\d+)] \\S+ \"재생목록 만들기").find(prompt.user)?.groupValues?.get(1)
                return if (id != null) """{"action":"click","id":$id,"check":false}""" else """{"action":"back","check":false}"""
            }
        }
        val result = Agent(model, phone, RecipeBook({ null }, {}), { emptyList() }, AgentConfig(withNote = false)).run("아이유 좋은날 재생목록에 추가해줘")
        val log = result.history.joinToString("\n")
        assertEquals(Outcome.DONE, result.outcome, log)
        assertEquals("아이유(IU) - 좋은 날 [가사/Lyrics]", phone.playlist.last(), log)
        assertEquals(3, phone.playlist.size, "added exactly once\n$log")
        assertEquals(0, phone.created, log)
        assertTrue(calls.isEmpty(), "no model call: $calls\n$log")
    }
}
