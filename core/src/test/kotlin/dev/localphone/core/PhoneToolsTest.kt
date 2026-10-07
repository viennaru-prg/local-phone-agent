package dev.localphone.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PhoneToolsTest {
    private val repo = object : UserPlacesRepository {
        override suspend fun all() = emptyList<UserPlace>()
        override suspend fun get(id: String): UserPlace? = null
        override suspend fun saveConfirmed(place: UserPlace) = error("Not used")
        override suspend fun delete(id: String) = error("Not used")
    }
    private val search = object : PlaceSearch { override suspend fun search(phrase: String) = SearchResult() }
    private class Device : DevicePort {
        var available = true
        var succeeds = true
        var requiresConfirmation = false
        val dispatched = mutableListOf<Action.Device>()
        override fun prepare(action: Action.Device, selectedAppId: String?) = DeviceCheck.Ready(
            PreparedDeviceCommand(action, "합성 작업", requiresConfirmation = requiresConfirmation))
        override fun canExecute(command: PreparedDeviceCommand) = available
        override fun dispatch(command: PreparedDeviceCommand): Boolean { dispatched += command.action; return succeeds }
    }
    private fun nav() = object : NavigationPort {
        override fun canLaunch(destination: PlaceCandidate) = error("No navigation requested")
        override fun launch(destination: PlaceCandidate) = error("No navigation requested")
    }
    private suspend fun parse(text: String) = BasicCommandPlanner().plan(text)

    @Test fun newPhoneCommandsAndExistingExamplesUseTheirOwnTools() = runTest {
        assertEquals(listOf(Action.OpenApp("시계")), parse("시계 앱 열어줘").actions)
        assertEquals(listOf(Action.OpenSettings(SettingsPage.WIFI)), parse("와이파이 설정 열어줘").actions)
        assertEquals(listOf(Action.OpenSettings(SettingsPage.BLUETOOTH)), parse("블루투스 설정 열어줘").actions)
        assertEquals(listOf(Action.OpenSettings(SettingsPage.GENERAL)), parse("설정 열어줘").actions)
        assertEquals(listOf(Action.MediaResume), parse("음악 켜줘").actions)
        assertEquals(listOf(Action.Navigate("집으로")), parse("집으로 네비 켜줘").actions)
        assertEquals(listOf(Action.OpenApp("음악")), parse("음악 앱 열어줘").actions)
    }
    @Test fun alarmUsesExplicitPeriodOrUnambiguous24HourTime() = runTest {
        for ((command, expected) in listOf(
            "오전 7시 30분 알람 맞춰줘" to Action.SetAlarm(7, 30),
            "오후 7시 알람 설정해줘" to Action.SetAlarm(19, 0),
            "오전 12시 알람 맞춰줘" to Action.SetAlarm(0, 0),
            "오후 12시 알람 맞춰줘" to Action.SetAlarm(12, 0),
            "19시 45분 알람 맞춰줘" to Action.SetAlarm(19, 45),
        )) assertEquals(listOf(expected), parse(command).actions, command)
    }
    @Test fun ambiguousAlarmTimeAndUnsupportedDatesRequireMoreInput() = runTest {
        for (command in listOf("7시 알람 맞춰줘", "오전 25시 알람 맞춰줘", "오전 7시 65분 알람 맞춰줘",
            "매일 오전 7시 알람 맞춰줘", "내일 오전 7시 알람 맞춰줘")) assertNotNull(parse(command).unsupportedReason, command)
    }
    @Test fun timerConvertsUnitsAndRejectsZeroOrHugeDuration() = runTest {
        assertEquals(listOf(Action.SetTimer(300)), parse("5분 타이머 시작해줘").actions)
        assertEquals(listOf(Action.SetTimer(3690)), parse("1시간 1분 30초 타이머 맞춰줘").actions)
        for (text in listOf("0초 타이머 시작해줘", "25시간 타이머 시작해줘", "타이머 시작해줘")) assertNotNull(parse(text).unsupportedReason)
    }
    @Test fun negativeAndQuotedPhoneCommandsNeverRun() = runTest {
        for (text in listOf("시계 앱 열어줘 하지 마", "5분 타이머 시작해줘 취소", "와이파이 설정 열어줘라는 예시 설명")) {
            assertTrue(parse(text).actions.isEmpty(), text)
        }
    }
    @Test fun modelArgumentsAreBoundedAndCannotInjectAndroidTargets() {
        for (call in listOf(
            RawToolCall("open_app", mapOf("app_name" to "Clock", "package" to "com.android.settings")),
            RawToolCall("open_app", mapOf("app_name" to "com.android.settings")),
            RawToolCall("open_app", mapOf("app_name" to "intent://arbitrary")),
            RawToolCall("set_alarm", mapOf("hour" to 7.25, "minute" to 0)),
            RawToolCall("set_alarm", mapOf("hour" to 24, "minute" to 0)),
            RawToolCall("set_timer", mapOf("seconds" to 0)),
            RawToolCall("set_timer", mapOf("seconds" to "300")),
            RawToolCall("open_settings", mapOf("page" to "arbitrary_activity")),
            RawToolCall("send_sms", mapOf("body" to "unrequested")),
        )) assertNotNull(ToolPlanDecoder.decode(listOf(call)).unsupportedReason, call.toString())
    }
    @Test fun modelCannotChangeTimeAppOrSettingsPage() {
        for ((action, input) in listOf(
            Action.SetAlarm(8, 0) to "오전 7시 알람 맞춰줘",
            Action.SetTimer(600) to "5분 타이머 시작해줘",
            Action.OpenApp("카메라") to "시계 앱 열어줘",
            Action.OpenApp("시계") to "시계 앱에 관해 설명해줘",
            Action.OpenApp("시계") to "시계 앱 열어줘라는 말의 뜻",
            Action.OpenSettings(SettingsPage.BLUETOOTH) to "와이파이 설정 열어줘",
        )) assertNotNull(PlanGrounding.validate(ToolPlan(listOf(action)), input).unsupportedReason)
        assertNull(PlanGrounding.validate(ToolPlan(listOf(Action.SetAlarm(7, 0))), "오전 7시 알람 맞춰줘").unsupportedReason)
    }
    @Test fun sameNameAppsNeedSelectionAndDoNotFuzzyMatch() {
        val apps = listOf(AppCandidate("a", "시계"), AppCandidate("b", "Clock", listOf("시계")), AppCandidate("c", "시계 메모"))
        assertEquals(listOf("a", "b"), AppNames.matching("시 계", apps).map { it.id })
        assertTrue(AppNames.matching("시게", apps).isEmpty())
    }
    @Test fun policyStopsAmbiguousAppWithoutAnyDispatch() = runTest {
        val choices = listOf(AppCandidate("a", "시계"), AppCandidate("b", "시계"))
        val device = object : DevicePort {
            override fun prepare(action: Action.Device, selectedAppId: String?) = DeviceCheck.Blocked("선택 필요", choices)
            override fun canExecute(command: PreparedDeviceCommand) = error("Must not execute")
            override fun dispatch(command: PreparedDeviceCommand) = error("Must not dispatch")
        }
        val blocked = assertIs<PolicyDecision.Blocked>(PolicyGate(PlaceResolver(repo, search), device).prepare(parse("시계 앱 열어줘")))
        assertEquals(choices, blocked.appChoices)
    }
    @Test fun newPhoneToolFlowsThroughSharedPolicyAndExecutor() = runTest {
        val device = Device()
        val ready = assertIs<PolicyDecision.Ready>(PolicyGate(PlaceResolver(repo, search), device).prepare(parse("5분 타이머 시작해줘")))
        assertNull(ready.destination)
        val result = ActionExecutor(nav(), object : MediaPort { override fun resume() = error("No music requested") }, device).execute(ready)
        assertTrue(result.deviceDispatched)
        assertFalse(result.launched || result.mediaResumed)
        assertEquals(listOf<Action.Device>(Action.SetTimer(300)), device.dispatched)
        assertTrue(result.message.contains(" 요청을 전달"))
    }
    @Test fun unavailableDeviceToolStopsMusicBeforeAnySideEffect() = runTest {
        val device = Device().apply { available = false }
        val result = ActionExecutor(nav(), object : MediaPort { override fun resume() = error("Must not resume") }, device)
            .execute(PolicyDecision.Ready(null, true, listOf(PreparedDeviceCommand(Action.SetTimer(300), "타이머"))))
        assertFalse(result.deviceDispatched || result.mediaResumed)
        assertTrue(device.dispatched.isEmpty())
    }
    @Test fun toolsMarkedForConfirmationCannotBypassExecutorGate() {
        val device = Device()
        val ready = PolicyDecision.Ready(null, false, listOf(PreparedDeviceCommand(Action.SetAlarm(7, 0), "합성 확인 작업", requiresConfirmation = true)))
        val executor = ActionExecutor(nav(), object : MediaPort { override fun resume() = false }, device)
        assertFalse(executor.execute(ready).deviceDispatched)
        assertTrue(device.dispatched.isEmpty())
        assertTrue(executor.execute(ready, confirmed = true).deviceDispatched)
    }
    @Test fun multiScreenGoalsCanBePreparedWithoutDispatchingDuringPlanning() = runTest {
        val device = Device()
        val result = PolicyGate(PlaceResolver(repo, search), device).prepare(ToolPlan(listOf(Action.OpenApp("시계"), Action.SetTimer(300))))
        assertEquals(2, assertIs<PolicyDecision.Ready>(result).deviceCommands.size)
        assertTrue(device.dispatched.isEmpty())
    }
    @Test fun toolRegistryCanRegisterAnotherToolWithoutChangingItsDecoder() {
        val extension = RegisteredTool("show_clock", "Test-only registration", emptyMap()) { Action.OpenApp("시계") }
        val registry = ToolRegistry(PhoneTools.registry.tools + extension)
        assertEquals(listOf(Action.OpenApp("시계")), registry.decode(listOf(RawToolCall("show_clock", emptyMap()))).actions)
        assertNotNull(PhoneTools.registry.decode(listOf(RawToolCall("show_clock", emptyMap()))).unsupportedReason)
        assertFailsWith<IllegalArgumentException> { ToolRegistry(listOf(extension, extension)) }
    }
}
