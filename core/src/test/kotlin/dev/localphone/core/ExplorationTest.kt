package dev.localphone.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A tiny settings app: root list → "보증 및 케어" (dead end) / scroll → "배터리". */
private class FakeSettings(private val scope: TestScope) : Phone {
    var screen = "root"
    private fun snap(vararg items: String) = Snapshot("settings", "설정",
        listOf(RawNode("r", -1, bounds = Bounds(0, 0, 1000, 2000))) + items.mapIndexed { i, t ->
            RawNode("r.$i", 0, text = t, clickable = true, bounds = Bounds(0, 300 + i * 100, 1000, 380 + i * 100))
        }, 1000, 2000)
    override suspend fun observe() = when (screen) {
        "root" -> snap("보증 및 케어", "소리 및 진동", "알림")
        "care" -> snap("상위 메뉴로 이동", "수리 요청", "원격 지원")
        "rootScrolled" -> snap("디스플레이", "배터리", "개발자 옵션")
        else -> snap("상위 메뉴로 이동", "배터리 사용량", "절전 모드")
    }
    override suspend fun perform(view: ScreenView, action: AgentAction): Boolean {
        val label = (action as? AgentAction.Click)?.let { view.element(it.id)?.label }
        screen = when {
            action is AgentAction.Scroll && screen == "root" -> "rootScrolled"
            label == "보증 및 케어" -> "care"
            label == "상위 메뉴로 이동" -> "root"
            label == "배터리" -> "battery"
            else -> return true
        }
        return true
    }
    override suspend fun openApp(name: String) = OpenAppResult(true, "이미 열려 있음")
    override suspend fun media(key: MediaKey) = false
    override fun now() = scope.testScheduler.currentTime
}

class ExplorationTest {
    @Test fun aScreenRevisitedExcludesTheDeadEndAndTheSearchGoesOn() = runTest {
        val phone = FakeSettings(this)
        val grammars = mutableListOf<String>()
        // A stubborn small model: always prefers "보증 및 케어" when it may, otherwise scrolls, then 배터리.
        val model = object : LanguageModel {
            override suspend fun decide(prompt: ModelPrompt, grammar: String): String {
                if (prompt.system == Prompts.VERIFY_SYSTEM) return """{"ok":true,"proofs":["s2"],"reason":"배터리 화면"}"""
                grammars += grammar
                val screen = prompt.user.substringAfter("현재 화면:").substringBefore("목표:")
                fun id(label: String) = Regex("\\[(\\d+)] \\S+ \"$label").find(screen)?.groupValues?.get(1)
                val care = id("보증 및 케어")
                return when {
                    "배터리 사용량" in screen -> """{"action":"done","say":"배터리 화면","check":true}"""
                    id("배터리") != null -> """{"action":"click","id":${id("배터리")},"check":false}"""
                    id("상위 메뉴로 이동") != null -> """{"action":"click","id":${id("상위 메뉴로 이동")},"check":false}"""
                    care != null && grammar.lines().first { it.startsWith("id ::=") }.contains("\"$care\"") -> """{"action":"click","id":$care,"check":false}"""
                    else -> """{"action":"scroll","dir":"down","check":false}"""
                }
            }
        }
        val result = Agent(model, phone, RecipeBook({ null }, {}), { emptyList() }, AgentConfig(withNote = false)).run("배터리 사용량 알려줘")
        println("RESULT ${result.outcome} ${result.say}\n" + result.history.joinToString("\n"))
        assertTrue(result.history.any { it.action == "다시 온 화면" }, result.history.joinToString("\n"))
        assertEquals("battery", phone.screen, result.history.joinToString("\n"))
        assertTrue(result.history.count { it.action == "click \"보증 및 케어\"" } == 1, "dead end opened only once")
    }
}
