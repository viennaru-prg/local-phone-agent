package dev.localphone.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GoalFallbackTest {
    private val emptyPlaces = object : UserPlacesRepository {
        override suspend fun all() = emptyList<UserPlace>()
        override suspend fun get(id: String): UserPlace? = null
        override suspend fun saveConfirmed(place: UserPlace) = Unit
        override suspend fun delete(id: String) = Unit
    }
    @Test fun officeWithoutLocalCacheRetainsGoalAndDoesNotQueryPublicApi() = runTest {
        val resolver = PlaceResolver(emptyPlaces, object : PlaceSearch { override suspend fun search(phrase: String): SearchResult = error("Private alias must resolve inside provider UI") })
        val ready = assertIs<PolicyDecision.Ready>(PolicyGate(resolver).prepare(BasicCommandPlanner().plan("회사로 가자")))
        assertNull(ready.destination); assertEquals("회사로", ready.navigationGoal)
    }
    @Test fun mapLaunchNeverReadsPlacesOrSearchCredentials() = runTest {
        val places = object : UserPlacesRepository by emptyPlaces { override suspend fun all(): List<UserPlace> = error("App launch cannot depend on places") }
        val device = object : DevicePort {
            override fun prepare(action: Action.Device, selectedAppId: String?) = DeviceCheck.Ready(PreparedDeviceCommand(action, "지도 열기", "discovered.map"))
            override fun canExecute(command: PreparedDeviceCommand) = true
            override fun dispatch(command: PreparedDeviceCommand) = true
        }
        val ready = assertIs<PolicyDecision.Ready>(PolicyGate(PlaceResolver(places, object : PlaceSearch {
            override suspend fun search(phrase: String): SearchResult = error("No map search for launch")
        }), device).prepare(BasicCommandPlanner().plan("지도 켜줘")))
        assertEquals(Action.OpenApp("지도"), ready.deviceCommands.single().action); assertNull(ready.navigationGoal)
    }
    @Test fun optionalSearchApiFailureKeepsOriginalDestinationForProviderUi() = runTest {
        val resolver = PlaceResolver(emptyPlaces, object : PlaceSearch { override suspend fun search(phrase: String) = SearchResult(permissionRequired = true) })
        val ready = assertIs<PolicyDecision.Ready>(PolicyGate(resolver).prepare(BasicCommandPlanner().plan("서울역 가자")))
        assertNull(ready.destination); assertEquals("서울역", ready.navigationGoal)
    }
    @Test fun unregisteredAppTaskKeepsVerbatimGoalAndDynamicAppName() {
        val plan = GoalRequests.fallback("새로운 메모에서 새 메모 눌러줘")
        assertEquals(Action.AppTask("새로운 메모", "새 메모 눌러줘"), plan.actions.single())
        assertNull(PlanGrounding.validate(plan, "새로운 메모에서 새 메모 눌러줘").unsupportedReason)
        assertNotNull(PlanGrounding.validate(plan, "계산기 켜줘").unsupportedReason)
    }
    @Test fun unavailableFastPathCannotReportMusicOnlyAsCompoundGoalSuccess() {
        val nav = object : NavigationPort { override fun canLaunch(destination: PlaceCandidate) = false; override fun launch(destination: PlaceCandidate) = error("No coordinates yet") }
        val media = object : MediaPort { override fun resume() = error("Destination must be resolved first") }
        assertFalse(ActionExecutor(nav, media).execute(PolicyDecision.Ready(null, true, navigationGoal = "회사로")).success)
    }
    @Test fun oneFuzzyCachedSuggestionIsNotARequiredQuestionBeforeProviderUi() = runTest {
        val suggestion = UserPlace("cafe", "테스트카페", emptyList(), Coordinates(36.1, 128.1), "합성 주소", "TEST", PlaceSource.MANUAL, 1, 1)
        val places = object : UserPlacesRepository by emptyPlaces { override suspend fun all() = listOf(suggestion) }
        val ready = assertIs<PolicyDecision.Ready>(PolicyGate(PlaceResolver(places, object : PlaceSearch { override suspend fun search(phrase: String): SearchResult = error("Fuzzy cache only") }))
            .prepare(BasicCommandPlanner().plan("테스트카폐 가자")))
        assertNull(ready.destination); assertEquals("테스트카폐", ready.navigationGoal)
    }
    @Test fun observedNodeTokensAndUserTextConstrainUiActions() {
        val screen = UiScreen("app", 1, listOf(UiNode("search", "장소 검색", editable = true), UiNode("start", "안내 시작", clickable = true), UiNode("secret", "비밀번호", editable = true)))
        assertTrue(UiGrounding.allowed("서울역 검색해줘", screen, UiCommand.SetText("search", "서울역")))
        assertFalse(UiGrounding.allowed("서울역 검색해줘", screen, UiCommand.SetText("search", "회사")))
        assertFalse(UiGrounding.allowed("서울역 검색해줘", screen, UiCommand.Click("invented")))
        assertFalse(UiGrounding.allowed("로그인", screen, UiCommand.SetText("secret", "1234")))
    }
    @Test fun acceptedRoutePreviewIsNotNavigationSuccess() {
        assertFalse(SemanticUi.navigationStarted(UiScreen("map", 1, listOf(UiNode("a", "회사"), UiNode("b", "안내 시작", clickable = true)))))
        assertFalse(SemanticUi.navigationStarted(UiScreen("map", 2, listOf(UiNode("a", "안내 종료", clickable = true)))))
        assertTrue(SemanticUi.navigationStarted(UiScreen("map", 3, listOf(UiNode("a", "안내 종료", clickable = true), UiNode("b", "주행 중 · 남은 거리 4km")))))
    }
    @Test fun sameLabelRowsAreDifferentIdentitiesAndUnclickableTextIsNotRootTap() {
        val first = UiScreen("map", 1, listOf(UiNode("row", "", clickable = true), UiNode("name", "회사", parent = "row"), UiNode("address", "서울시 테스트로 11", parent = "row")))
        val replaced = first.copy(nodes = first.nodes.map { if (it.token == "address") it.copy(label = "서울시 테스트로 22") else it })
        assertNotEquals(UiFingerprint.describe(first, first.node("row")!!), UiFingerprint.describe(replaced, replaced.node("row")!!))
        val text = UiNode("label", "검색", parent = "root")
        assertEquals(text, UiScreen("map", 1, listOf(UiNode("root", ""), text)).clickTarget(text))
    }
    @Test fun observedNavigationStartMatchesCountdownButNotExplanatoryOrEndLabels() {
        for (label in listOf("안내 시작", "안내 시작 (3초)", "안내 시작 5", "5초 후 안내 시작", "Start navigation (3 seconds)")) {
            val screen = UiScreen("map", 1, listOf(UiNode("start", label, clickable = true)))
            assertEquals(listOf("start"), SemanticUi.navigationStartTargets(screen).map { it.token }, label)
        }
        for (label in listOf("안내 종료", "안내 시작 방법", "안내 시작을 취소", "시작 안내", "회사"))
            assertTrue(SemanticUi.navigationStartTargets(UiScreen("map", 1, listOf(UiNode("x", label, clickable = true)))).isEmpty(), label)
    }
    @Test fun disabledStartRemainsObservableButCannotBeClickedUntilRefreshed() {
        val screen = UiScreen("map", 1, listOf(UiNode("row", "", clickable = true, enabled = false), UiNode("label", "안내 시작 (2초)", parent = "row")))
        val start = SemanticUi.navigationStartTargets(screen).single()
        assertEquals("row", start.token); assertFalse(start.enabled)
        assertFalse(UiGrounding.allowed("회사로 안내 시작", screen, UiCommand.Click(start.token)))
        val ready = screen.copy(nodes = screen.nodes.map { it.copy(enabled = true) })
        assertTrue(UiGrounding.allowed("회사로 안내 시작", ready, UiCommand.Click(start.token)))
        assertNotEquals(UiFingerprint.describe(screen, start), UiFingerprint.describe(ready, ready.node("row")!!))
    }
    @Test fun ordinaryCompletionTextIsEvidenceAndCannotBecomeAGestureTarget() {
        val screen = UiScreen("app", 1, listOf(UiNode("done", "테스트 메모 저장 완료")))
        assertFalse(UiGrounding.allowed("테스트 메모를 저장해줘", screen, UiCommand.Click("done")))
    }
    @Test fun navigationStartSupportsOsClickActionAndObservedSemanticButton() {
        for (node in listOf(UiNode("start", "안내 시작", clickAction = true),
            UiNode("start", "안내 시작 · 5초 후 자동 시작", role = "android.widget.Button", tapEligible = true))) {
            val screen = UiScreen("map", 1, listOf(node))
            assertEquals(listOf("start"), SemanticUi.navigationStartTargets(screen).map { it.token })
            assertTrue(UiGrounding.allowed("회사로 안내 시작", screen, UiCommand.Click("start", gesture = true)))
        }
        assertTrue(SemanticUi.navigationStartTargets(UiScreen("map", 1, listOf(UiNode("label", "안내 시작")))).isEmpty())
    }
    @Test fun drivingTelemetryCanConfirmActiveGuidanceWithEndControlHidden() {
        val nodes = listOf(UiNode("speed", "0 km/h"), UiNode("distance", "4.2 km"),
            UiNode("arrival", "12:30 도착"), UiNode("voice", "음성 안내", clickable = true))
        assertTrue(SemanticUi.navigationStarted(UiScreen("map", 1, nodes)))
        assertFalse(SemanticUi.navigationStarted(UiScreen("map", 2, nodes.drop(1))))
        assertFalse(SemanticUi.navigationStarted(UiScreen("map", 3, nodes + UiNode("start", "안내 시작", clickable = true))))
        assertFalse(SemanticUi.navigationStarted(UiScreen("map", 4, nodes.filter { it.token != "voice" })))
    }
    @Test fun explicitDestinationEvidenceRemainsSeparateFromDrivingTelemetry() {
        val screen = UiScreen("map", 1, listOf(UiNode("destination", "목적지: 서울역"), UiNode("arrival", "12:30 도착")))
        assertEquals(listOf("서울역"), SemanticUi.destinationLabels(screen))
        assertFalse(SemanticUi.navigationStarted(screen))
    }
}
