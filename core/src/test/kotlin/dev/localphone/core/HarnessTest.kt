package dev.localphone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HarnessTest {
    private fun view(goal: String, vararg elements: List<String>) = Evaluator.view(EvalCase("t", goal, "앱", elements.toList(), emptyList()))

    @Test fun resumePromptIsDeclinedForANewCommand() {
        val v = view("회사로 안내해줘",
            listOf("항목", "기본 대화상자 헤더 아이콘 서울 관악구 성현로 80까지 내비게이션 안내를 이어서 받으시겠습니까?"),
            listOf("항목", "아니요"), listOf("항목", "경로안내"))
        assertEquals(AgentAction.Click(2), Harness.preDecide("회사로 안내해줘", v)?.action)
    }

    @Test fun resumePromptIsLeftToTheModelWhenTheUserAsksToContinue() {
        val v = view("안내 이어서 해줘", listOf("항목", "안내를 이어서 받으시겠습니까?"), listOf("항목", "아니요"), listOf("항목", "경로안내"))
        assertNull(Harness.preDecide("안내 이어서 해줘", v))
    }

    @Test fun ordinaryScreensAreLeftToTheModel() {
        val v = view("회사로 안내해줘", listOf("항목", "집"), listOf("항목", "회사"), listOf("버튼", "취소"))
        assertNull(Harness.preDecide("회사로 안내해줘", v))
    }

    @Test fun commitFollowsTheChosenTarget() {
        val route = view("회사로 안내해줘", listOf("항목", "경기 수원시 팔달구 고등동 187"), listOf("버튼", "출발지 도착지 전환"),
            listOf("항목", "서울특별시 중구 세종대로 110"), listOf("항목", "실시간 추천 13분"), listOf("항목", "나중에 출발"), listOf("항목", "안내시작"))
        val afterTarget = listOf(HistoryLine("open_app \"네이버 지도\"", "열림"), HistoryLine("click \"길찾기\"", "화면 바뀜"),
            HistoryLine("click \"회사\"", "화면 바뀜"), HistoryLine("wait", "화면 바뀜", "확정 버튼이 아직 눌리지 않는 상태라 기다림"))
        assertEquals(AgentAction.Click(6), Harness.preDecide("회사로 안내해줘", route, afterTarget)?.action)
        // Without a just-chosen target the model decides.
        assertNull(Harness.preDecide("회사로 안내해줘", route, afterTarget.take(2)))
    }

    @Test fun searchGoalsOpenTheFieldThenTypeTheQuery() {
        assertEquals("카페", GoalText.searchQuery("네이버 지도에서 카페 검색해줘"))
        assertEquals("고양이", GoalText.searchQuery("유튜브에서 고양이 검색해줘"))
        val opened = listOf(HistoryLine("open_app \"유튜브\"", "열림: YouTube"))
        val home = view("유튜브에서 고양이 검색해줘", listOf("항목", "홈"), listOf("버튼", "음성 검색"), listOf("버튼", "검색"))
        assertEquals(AgentAction.Click(3), Harness.preDecide("유튜브에서 고양이 검색해줘", home, opened)?.action)
        val field = view("유튜브에서 고양이 검색해줘", listOf("버튼", "뒤로"), listOf("입력칸", "YouTube 검색"))
        assertEquals(AgentAction.Type(2, "고양이", true), Harness.preDecide("유튜브에서 고양이 검색해줘", field, opened)?.action)
        val typed = opened + HistoryLine("type \"YouTube 검색\" \"고양이\" +enter", "화면 바뀜")
        assertNull(Harness.preDecide("유튜브에서 고양이 검색해줘", field, typed))
    }

    @Test fun timerStartTextIsNotMistakenForLoading() {
        val timer = view("시계 앱에서 타이머 화면 열어줘", listOf("항목", "타이머", "selected"), listOf("텍스트", "시작"))
        assertNull(Harness.preDecide("시계 앱에서 타이머 화면 열어줘", timer, listOf(HistoryLine("click \"타이머\"", "화면 바뀜"))))
    }

    @Test fun namedItemIsOpenedButSwitchesAreNotToggled() {
        val goal = "설정에서 블루투스 화면 열어줘"
        val connections = view(goal, listOf("항목", "Wi-Fi"), listOf("항목", "블루투스"), listOf("스위치", "블루투스", "on"), listOf("항목", "모바일 핫스팟 및 테더링"))
        assertEquals(AgentAction.Click(2), Harness.preDecide(goal, connections, listOf(HistoryLine("click \"연결\"", "화면 바뀜")))?.action)
        val onlySwitch = view(goal, listOf("항목", "Wi-Fi"), listOf("스위치", "블루투스", "on"))
        assertNull(Harness.preDecide(goal, onlySwitch, listOf(HistoryLine("click \"연결\"", "화면 바뀜"))))
    }

    @Test fun searchIsNotDoneUntilTheQueryIsVisible() {
        val goal = "Play 스토어에서 카카오맵 검색해줘"
        assertEquals(false, Harness.searchShown(goal, view(goal, listOf("입력칸", "Google Play 검색"))))
        assertEquals(true, Harness.searchShown(goal, view(goal, listOf("입력칸", "Google Play 검색", "value=카카오맵"))))
        assertEquals(false, Commit.isCommit("Google Play 검색"))
        assertEquals(true, Commit.isCommit("안내시작 10"))
    }

    @Test fun koreanNamesFindEnglishLabelsAndWrongScreensAreRejected() {
        val goal = "설정에서 와이파이 화면 열어줘"
        val list = view(goal, listOf("항목", "연결"), listOf("항목", "Wi-Fi"), listOf("항목", "배경화면 및 스타일"))
        assertEquals(AgentAction.Click(2), Harness.preDecide(goal, list, listOf(HistoryLine("open_app \"설정\"", "열림: 설정")))?.action)
        assertEquals(false, Harness.screenShown(goal, view(goal, listOf("텍스트", "배경화면 및 스타일"), listOf("항목", "테마"))))
        assertEquals(true, Harness.screenShown(goal, view(goal, listOf("텍스트", "Wi-Fi"), listOf("스위치", "Wi-Fi", "on"))))
        // One shared word is not the screen: "보안 및 개인정보 보호" for "소프트웨어 정보".
        val info = "설정에서 소프트웨어 정보 보여줘"
        assertEquals("소프트웨어 정보", ShortcutGoals.screenName(info))
        assertEquals("디스플레이", ShortcutGoals.screenName("설정에서 디스플레이로 들어가줘"))
        assertNull(ShortcutGoals.screenName("갤러리에서 최근 사진 보여줘"))
        val display = "설정에서 디스플레이로 들어가줘"
        assertEquals(true, Harness.openScreenEvidence(display, view(display, listOf("텍스트", "디스플레이"), listOf("항목", "밝기"))))
        val results = view(display, listOf("입력칸", "무엇을 찾고 있나요?", "value=디스플레이"), listOf("항목", "디스플레이 · 최근 사용한 설정"), listOf("항목", "디스플레이"))
        assertEquals(AgentAction.Click(3), Harness.preDecide(display, results, listOf(HistoryLine("open_app \"설정\"", "열림: 설정"),
            HistoryLine("type \"무엇을 찾고 있나요?\" \"디스플레이\" +enter", "화면 바뀜")))?.action)
        assertEquals(false, Harness.screenShown(info, view(info, listOf("텍스트", "보안 및 개인정보 보호"), listOf("항목", "생체 인식"))))
        assertEquals(true, Harness.screenShown(info, view(info, listOf("텍스트", "소프트웨어 정보"), listOf("항목", "One UI 버전"))))
        // The settings search opens the parent page with the row highlighted: still a row to press.
        assertEquals(true, Harness.screenStillListed(info, view(info, listOf("텍스트", "폰 정보"), listOf("항목", "상태 정보"), listOf("항목", "소프트웨어 정보"))))
        assertEquals(false, Harness.screenStillListed(info, view(info, listOf("텍스트", "소프트웨어 정보"), listOf("항목", "One UI 버전"))))
    }

    @Test fun aNamedAppKeepsOtherAppNotesAway() {
        val notes = listOf("[음악, 노래] 음악은 ClipStream 앱을 쓴다.")
        assertEquals(emptyList(), GoalText.relevantNotes("유튜브에서 아이유 노래 검색해줘", notes))
        assertEquals(1, GoalText.relevantNotes("아이유 노래 틀어줘", notes).size)
        assertEquals("시계", GoalText.namedApp("시계 앱에서 타이머 화면 열어줘"))
    }

    @Test fun lockScreensAreRecognized() {
        assertEquals(true, Harness.authScreen(view("x", listOf("텍스트", "보안 폴더"), listOf("텍스트", "잠금해제 패턴을 그리세요"))))
        assertEquals(false, Harness.authScreen(view("x", listOf("항목", "배터리"), listOf("항목", "디바이스 케어"))))
    }

    @Test fun onlyMusicOrNavigationWordsCostARouterCall() {
        assertEquals(false, Router.mightUseTool("유튜브에서 고양이 검색해줘"))
        assertEquals(false, Router.mightUseTool("설정에서 배터리 화면 열어줘"))
        assertEquals(true, Router.mightUseTool("노래 멈춰"))
        assertEquals(true, Router.mightUseTool("학교로 가자"))
    }

    @Test fun recipeMatchesCountdownLabelsWithDifferentNumbers() {
        val v = view("x", listOf("항목", "나중에 출발"), listOf("항목", "안내시작 7"))
        assertEquals(2, RecipeRecorder.locate(RecipeStep("click", "안내시작 10", "ITEM"), v)?.id)
    }
}
