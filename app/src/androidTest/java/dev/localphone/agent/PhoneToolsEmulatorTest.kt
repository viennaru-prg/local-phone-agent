package dev.localphone.agent

import android.content.Intent
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.containsString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real emulator Clock/Settings apps, without intent stubs. Never clear a user's physical Clock. */
@RunWith(AndroidJUnit4::class)
class PhoneToolsEmulatorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private val clock = "com.google.android.deskclock"
    private var scenario: ActivityScenario<MainActivity>? = null
    private var dedicated = false
    private var intentsReady = false

    @Before fun before() {
        graph.agentModelEnabledOverride = false
        dedicated = device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "LocalPhoneAgent_API35"
        Assume.assumeTrue("Only the task-owned LocalPhoneAgent_API35 AVD is allowed", dedicated)
        assertTrue(device.executeShellCommand("pm clear $clock").contains("Success"))
        runBlocking { graph.places.all().forEach { graph.places.delete(it.id) } }
        graph.settings.put("search_enabled", "")
        graph.settings.put("music_package", "")
        Intents.init(); intentsReady = true
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onView(withId(R.id.agent_settings)).perform(click())
        await { onView(withText("미리 등록하지 않아도 지도 앱의 저장 장소와 검색 화면에서 찾습니다.")).check(matches(isDisplayed())) }
    }
    @After fun after() {
        graph.agentModelEnabledOverride = null
        scenario?.close()
        if (intentsReady) Intents.release()
        if (dedicated) device.executeShellCommand("pm clear $clock") // Remove only the synthetic alarms/timers we created.
    }
    private fun await(assertion: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var failure: Throwable? = null
        do {
            instrumentation.waitForIdleSync()
            try { assertion(); return } catch (error: AssertionError) { failure = error }
            catch (error: androidx.test.espresso.NoMatchingViewException) { failure = error }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw failure ?: AssertionError("Condition timed out")
    }
    private fun command(text: String) {
        onView(withId(R.id.command_input)).perform(scrollTo(), replaceText(text))
        closeSoftKeyboard()
        onView(withText("명령 실행")).perform(scrollTo(), click())
    }
    private fun statusContains(text: String) = await {
        onView(withId(R.id.command_status)).check(matches(withText(containsString(text))))
    }
    private fun screenshot(name: String, agentScreen: Boolean = false) {
        if (agentScreen) {
            scenario?.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
        }
        val folder = File(context.getExternalFilesDir(null), "emulator-evidence").apply { mkdirs() }
        SystemClock.sleep(350)
        assertTrue(device.takeScreenshot(File(folder, "$name.png")))
    }
    private fun noClockRequests() = assertTrue(Intents.getIntents().none {
        it.action in listOf(AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SET_TIMER)
    })

    @Test fun opensActualInstalledClockByLocalAppName() {
        command("시계 앱 열어줘")
        await { assertEquals(clock, device.currentPackageName) }
        val launch = Intents.getIntents().single { it.action == Intent.ACTION_MAIN && it.component?.packageName == clock }
        assertTrue(launch.categories.contains(Intent.CATEGORY_LAUNCHER))
        assertTrue(Intents.getIntents().none { it.data?.scheme == "nmap" })
        screenshot("04-real-clock-app")
    }
    @Test fun opensActualWifiSettingsWithoutChangingWifiState() {
        val before = device.executeShellCommand("settings get global wifi_on").trim()
        command("와이파이 설정 열어줘")
        await { assertEquals("com.android.settings", device.currentPackageName) }
        assertEquals(1, Intents.getIntents().count { it.action == Settings.ACTION_WIFI_SETTINGS })
        assertEquals(before, device.executeShellCommand("settings get global wifi_on").trim())
        screenshot("05-real-wifi-settings")
    }
    @Test fun createsAlarmThroughRealClockWithExactRequestedTime() {
        command("오전 7시 30분 알람 맞춰줘")
        await { assertEquals(clock, device.currentPackageName) }
        val request = Intents.getIntents().single { it.action == AlarmClock.ACTION_SET_ALARM }
        assertEquals(7, request.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, request.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertFalse(request.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
        await { assertTrue("Clock did not show the requested alarm", device.findObjects(By.textContains("7:30")).isNotEmpty()) }
        screenshot("06-real-alarm")
    }
    @Test fun requestsFiveMinuteTimerThroughRealClock() {
        command("5분 타이머 시작해줘")
        await { assertEquals(clock, device.currentPackageName) }
        val request = Intents.getIntents().single { it.action == AlarmClock.ACTION_SET_TIMER }
        assertEquals(300, request.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertFalse(request.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
        screenshot("07-real-timer")
    }
    @Test fun unknownAppNeverLaunchesAnArbitraryAlternative() {
        command("없는합성앱999 앱 열어줘")
        statusContains("앱을 찾지 못했습니다")
        assertEquals(context.packageName, device.currentPackageName)
        assertTrue(Intents.getIntents().none { it.action == Intent.ACTION_MAIN })
        screenshot("08-general-agent-unknown-app", agentScreen = true)
    }
    @Test fun missingAlarmPeriodDoesNotGuessMorningOrEvening() {
        command("7시 알람 맞춰줘")
        statusContains("오전인지 오후인지")
        noClockRequests()
        assertEquals(context.packageName, device.currentPackageName)
    }
    @Test fun zeroLengthTimerDoesNotDispatchToClock() {
        command("0초 타이머 시작해줘")
        statusContains("1초부터 24시간")
        noClockRequests()
        assertEquals(context.packageName, device.currentPackageName)
    }
}
