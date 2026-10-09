package dev.localphone.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A tiny fake "NAVER Map": launcher → map → favorites → place → route → driving. */
private class FakeMap(private val scope: TestScope) : Phone {
    var screen = "home"
    val actions = mutableListOf<String>()
    private fun row(path: String, top: Int, vararg texts: String, clickable: Boolean = true) = texts.mapIndexed { i, t ->
        RawNode("$path.$i", -2, text = t, bounds = Bounds(0, top + i * 40, 1000, top + i * 40 + 40))
    }.let { children -> listOf(RawNode(path, 0, clickable = clickable, className = "View", bounds = Bounds(0, top, 1000, top + 80))) + children }

    private fun build(pkg: String, label: String, items: List<List<RawNode>>): Snapshot {
        val nodes = mutableListOf(RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000)))
        for (group in items) {
            val parentIndex = nodes.size
            group.forEachIndexed { i, n -> nodes += if (i == 0) n.copy(parent = 0) else n.copy(parent = parentIndex) }
        }
        return Snapshot(pkg, label, nodes, 1000, 2000)
    }

    override suspend fun observe(): Snapshot = when (screen) {
        "home" -> build("launcher", "홈", listOf(row("r.0", 500, "네이버 지도"), row("r.1", 700, "ClipStream")))
        "map" -> build("nmap", "네이버 지도", listOf(row("r.0", 300, "검색"), row("r.1", 1900, "저장")))
        "saved" -> build("nmap", "네이버 지도", listOf(row("r.0", 300, "집", "경기 수원시"), row("r.1", 500, "회사", "서울 강남구")))
        "place" -> build("nmap", "네이버 지도", listOf(row("r.0", 300, "회사"), row("r.1", 1700, "도착")))
        "route" -> build("nmap", "네이버 지도", listOf(row("r.0", 300, "자동차 32분"), row("r.1", 1700, "안내 시작")))
        "driving" -> build("nmap", "네이버 지도", listOf(row("r.0", 300, "안내 종료", clickable = false), row("r.1", 500, "남은 거리 12km", clickable = false)))
        else -> error(screen)
    }

    override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
        val label = when (action) { is AgentAction.Click -> view.element(action.id)?.label; else -> null }
        actions += action.describe(view)
        screen = when (screen to label) {
            "map" to "저장" -> "saved"
            "saved" to "회사 서울 강남구" -> "place"
            "place" to "도착" -> "route"
            "route" to "안내 시작" -> "driving"
            else -> return true // accepted but nothing happens
        }
        return true
    }

    override suspend fun openApp(name: String): OpenAppResult {
        actions += "open $name"
        return if (name.contains("지도")) { screen = "map"; OpenAppResult(true, "열림") } else OpenAppResult(false, "앱 없음")
    }
    override suspend fun media(key: MediaKey) = true
    override fun now() = scope.testScheduler.currentTime
}

/** Scripted stand-in for the LLM: picks by the current screen, like a model would. */
private class ScriptedModel : LanguageModel {
    var calls = 0
    var verifications = 0
    /** Simulates the classic small-model slip: claiming success right after opening the app. */
    var prematureDone = false
    override suspend fun decide(prompt: ModelPrompt, grammar: String): String {
        val screen = prompt.user.substringAfter("현재 화면:")
        if ("완료 확인" in prompt.user) {
            verifications++
            return if ("안내 종료" in screen) """{"ok":true,"reason":"회사로 안내를 시작했어요"}""" else """{"ok":false,"reason":"아직 안내가 시작되지 않음"}"""
        }
        calls++
        if (prematureDone && "\"저장\"" in screen) { prematureDone = false; return """{"note":"","action":"done","say":"했어요"}""" }
        fun id(label: String) = Regex("\\[(\\d+)] \\S+ \"$label").find(screen)!!.groupValues[1]
        return when {
            "안내 종료" in screen -> """{"note":"주행 화면","action":"done","say":"회사로 안내를 시작했어요"}"""
            "안내 시작" in screen -> """{"note":"","action":"click","id":${id("안내 시작")}}"""
            "\"도착\"" in screen -> """{"note":"","action":"click","id":${id("도착")}}"""
            "\"회사 서울" in screen -> """{"note":"저장 목록의 회사","action":"click","id":${id("회사 서울")}}"""
            "\"저장\"" in screen -> """{"note":"저장 목록 확인","action":"click","id":${id("저장")}}"""
            else -> """{"note":"지도 앱 필요","action":"open_app","app":"네이버 지도"}"""
        }
    }
}

class AgentTest {
    @Test fun modelDrivesWholeNavigationThenRecipeReplaysIt() = runTest {
        val store = arrayOf<String?>(null)
        val book = RecipeBook({ store[0] }, { store[0] = it })
        val phone = FakeMap(this)
        val model = ScriptedModel()
        val agent = Agent(model, phone, book, { listOf("길안내는 네이버 지도의 저장 목록을 먼저 확인") })

        val first = agent.run("회사로 안내해줘")
        assertEquals(Outcome.DONE, first.outcome, first.toString())
        assertEquals("회사로 안내를 시작했어요", first.say)
        assertTrue(first.learned)
        // open, 저장, 회사, 도착, 안내 시작 — the harness verifies right after "안내 시작", no separate "done" call.
        assertEquals(5, model.calls)
        assertEquals(1, model.verifications)
        assertEquals(5, book.get("회사로 안내해 줘")!!.steps.size)
        assertEquals(null, book.find("회사로 안내해 줘"), "unconfirmed paths are not replayed")
        book.confirm("회사로 안내해줘")

        phone.screen = "home"; model.calls = 0
        val second = agent.run("회사로 안내해 줘")
        assertEquals(Outcome.DONE, second.outcome, second.toString())
        assertEquals(1, model.calls, "replay should leave only the final confirmation to the model")
        assertEquals(1, book.find("회사로 안내해줘")!!.uses)
    }

    @Test fun swapButtonIsNeverPressedForANavigationGoal() {
        val snap = Snapshot("p", "지도", listOf(RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000)),
            RawNode("r.0", 0, desc = "출발지 도착지 전환", clickable = true, className = "Button", bounds = Bounds(0, 500, 100, 560))), 1000, 2000)
        val view = ScreenCompactor.compact(snap)
        assertTrue(Guard.blocked("회사로 안내해줘", AgentAction.Click(1), view)!!.contains("바꾸는"))
    }

    @Test fun harnessWaitsWhileTheStartButtonIsStillPlainText() {
        val loading = Evaluator.view(EvalCase("t", "회사로 안내해줘", "지도", listOf(listOf("버튼", "출발지 도착지 전환"), listOf("텍스트", "안내시작")), emptyList()))
        val picked = listOf(HistoryLine("click \"회사\"", "화면 바뀜"))
        assertEquals(AgentAction.Wait, Harness.preDecide("회사로 안내해줘", loading, picked)?.action)
        assertEquals(null, Harness.preDecide("회사로 안내해줘", loading), "no waiting before anything was picked")
        val ready = Evaluator.view(EvalCase("t", "회사로 안내해줘", "지도", listOf(listOf("항목", "안내시작 10")), emptyList()))
        assertEquals(null, Harness.preDecide("회사로 안내해줘", ready))
    }

    @Test fun prematureDoneIsRejectedByVerificationAndWorkContinues() = runTest {
        val store = arrayOf<String?>(null)
        val book = RecipeBook({ store[0] }, { store[0] = it })
        val model = ScriptedModel().apply { prematureDone = true }
        val result = Agent(model, FakeMap(this), book, { emptyList() }).run("회사로 안내해줘")
        assertEquals(Outcome.DONE, result.outcome, result.toString())
        assertEquals(2, model.verifications)
        assertTrue(result.history.any { it.outcome.startsWith("검증 실패") })
        assertEquals(5, book.get("회사로 안내해줘")!!.steps.size)
    }

    @Test fun replayFallsBackToModelWhenLabelMissing() = runTest {
        val store = arrayOf<String?>(null)
        val book = RecipeBook({ store[0] }, { store[0] = it })
        book.put(Recipe(GoalKey.of("회사로 안내해줘"), "회사로 안내해줘", listOf(
            RecipeStep("open_app", app = "네이버 지도"), RecipeStep("click", "즐겨찾기", "ITEM")), "", 0, confirmed = true))
        val phone = FakeMap(this)
        val model = ScriptedModel()
        val result = Agent(model, phone, book, { emptyList() }).run("회사로 안내해줘")
        assertEquals(Outcome.DONE, result.outcome)
        assertTrue(result.history.any { "즐겨찾기" in it.action && "화면에 없음" in it.action })
    }

    @Test fun guardBlocksPaymentEvenIfModelChoosesIt() {
        val snap = Snapshot("p", "앱", listOf(RawNode("r", -1, text = "결제하기", clickable = true, bounds = Bounds(0, 0, 10, 10))), 100, 100)
        val view = ScreenCompactor.compact(snap)
        assertEquals("보호된 동작이라 실행하지 않음", Guard.blocked("음악 추가해줘", AgentAction.Click(1), view))
    }
}
