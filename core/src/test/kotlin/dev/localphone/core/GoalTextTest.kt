package dev.localphone.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalTextTest {
    @Test fun wordsDropParticlesAndEndings() {
        assertEquals(listOf("회사", "안내"), GoalText.words("회사로 안내해줘"))
        assertEquals(listOf("와이파이"), GoalText.words("와이파이 켜줘"))
        assertEquals(listOf("네이버지도"), GoalText.words("네이버지도 열어"))
        assertEquals(listOf("아이유", "노래", "검색"), GoalText.words("아이유 노래 검색해줘"))
    }

    @Test fun starMarksOnlyGoalTargets() {
        val case = EvalCase("t", "회사로 안내해줘", "지도", listOf(listOf("항목", "집"), listOf("항목", "회사")), emptyList())
        val text = Evaluator.view(case).render(GoalText.words(case.goal))
        assertTrue("[2] 항목 \"회사\" ★" in text, text)
        assertTrue("[1] 항목 \"집\"\n" in text, text)
    }

    @Test fun spokenResultsAreShort() {
        assertEquals("'고양이' 검색했어요.", GoalText.spokenResult("유튜브에서 고양이 검색해줘", "아주 긴 설명입니다. 두 번째 문장."))
        assertEquals("배터리 화면을 열었어요.", GoalText.spokenResult("설정에서 배터리 화면 열어줘", "x"))
        assertEquals("카카오톡 열었어요.", GoalText.spokenResult("카카오톡 열어줘", "x"))
        assertEquals("회사로 안내를 시작했어요.", GoalText.spokenResult("회사로 안내해줘", "회사로 안내를 시작했어요. 덧붙인 설명."))
    }

    @Test fun chatterIsRecognizedButCommandsAreNot() {
        for (s in listOf("켰어", "응 알았어 고마워", "네", "됐어")) assertTrue(GoalText.isChatter(s), s)
        for (s in listOf("회사로 안내해줘", "와이파이 켜줘", "음악 재생해", "지도 켜")) assertTrue(!GoalText.isChatter(s), s)
    }

    @Test fun doneEvidenceComesFromResultTextNotFromChoices() {
        val driving = EvalCase("d", "회사로 안내해줘", "지도", listOf(listOf("텍스트", "목적지 회사"), listOf("항목", "안내 종료")), emptyList(),
            listOf(listOf("click \"안내시작\"", "화면 바뀜")))
        assertEquals(listOf("목적지 회사"), Prompts.doneEvidence(driving.goal, Evaluator.history(driving), Evaluator.view(driving)))
        val choices = driving.copy(elements = listOf(listOf("항목", "회사"), listOf("항목", "집")))
        assertTrue(Prompts.doneEvidence(choices.goal, Evaluator.history(choices), Evaluator.view(choices)).isEmpty())
        val fresh = driving.copy(history = null)
        assertTrue(Prompts.doneEvidence(fresh.goal, Evaluator.history(fresh), Evaluator.view(fresh)).isEmpty())
    }

    @Test fun noteFreeGrammarAndExamples() {
        val view = Evaluator.view(EvalCase("t", "x", "앱", listOf(listOf("버튼", "확인")), emptyList()))
        assertTrue("\"{\\\"action\\\":\"" in ActionGrammar.forView(view, withNote = false))
        assertTrue("\"note\"" !in Prompts.SYSTEM_NO_NOTE)
    }

    @Test fun taggedNotesShowOnlyForMatchingGoals() {
        val notes = listOf("[음악, 노래] 음악은 ClipStream", "[안내, 가자] 지도는 네이버", "항상 보이는 메모")
        assertEquals(listOf("음악은 ClipStream", "항상 보이는 메모"), GoalText.relevantNotes("음악 재생해줘", notes))
        assertEquals(listOf("지도는 네이버", "항상 보이는 메모"), GoalText.relevantNotes("집으로 가자", notes))
    }

    @Test fun evalCasesParseAndExpectedTargetsExist() {
        val file = File("../app/src/main/assets/eval-cases.json")
        val cases = Evaluator.load(file.readText())
        assertTrue(cases.size >= 20)
        for (case in cases) {
            val view = Evaluator.view(case)
            if (view.snapshot.home) continue // home screens hide their icons; only open_app applies
            for (want in case.expect.filter { it.startsWith("click:") }) {
                val label = want.removePrefix("click:")
                assertTrue(view.elements.any { GoalText.normalize(it.label) == GoalText.normalize(label) }, "${case.name}: no element '$label'")
            }
        }
    }
}
