package dev.localphone.agent

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.localphone.agent.runtime.AgentEngine
import dev.localphone.agent.runtime.SpeechInput
import dev.localphone.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual installed manifest and unavailable-tool behavior; no Play Protect verdict is simulated. */
@RunWith(AndroidJUnit4::class)
class InstallPackageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var intentsReady = false

    private class FakeSpeech : SpeechInput {
        @Volatile var listener: SpeechInput.Listener? = null
        override fun start(listener: SpeechInput.Listener) { this.listener = listener; listener.onListening() }
        override fun cancel() = Unit
    }

    @Before fun before() {
        assertEquals("LocalPhoneAgent_API35", device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        assertFalse(BuildConfig.MEDIA_SESSION_CONTROL_AVAILABLE)
        graph.settings.put("use_functiongemma", "")
        graph.settings.put("voice_onboarded", "yes")
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        Intents.init(); intentsReady = true
    }
    @After fun after() {
        voice()?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        scenario?.close()
        graph.speechFactoryOverride = null
        if (intentsReady) Intents.release()
    }
    private fun voice(): VoiceInvocationActivity? {
        var result: VoiceInvocationActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<VoiceInvocationActivity>().singleOrNull()
        }
        return result
    }
    private fun await(assertion: () -> Unit) {
        val end = SystemClock.elapsedRealtime() + 10_000
        var failure: AssertionError? = null
        do {
            try { assertion(); return } catch (error: AssertionError) { failure = error }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < end)
        throw failure ?: AssertionError("Timed out")
    }

    @Test fun installedPackageHasNoNotificationSmsOrAccessibilityPrivileges() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.RECORD_AUDIO in permissions)
        assertTrue(permissions.intersect(setOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, Manifest.permission.BIND_ACCESSIBILITY_SERVICE)).isEmpty())
        assertTrue(info.services.orEmpty().none { it.permission in setOf(
            Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, Manifest.permission.BIND_ACCESSIBILITY_SERVICE) })
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY)
        assertTrue(runCatching { context.classLoader.loadClass("dev.localphone.agent.runtime.AgentNotificationListener") }.isFailure)
    }

    @Test fun unavailableFastPathDoesNotRejectGoalPreparationOrDispatchDuringPlanning() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        scenario!!.onActivity { activity ->
            val engine = AgentEngine(activity, graph)
            assertFalse(engine.media.hasPermission())
            assertTrue(engine.media.controllers().isEmpty())
            assertTrue(runCatching { engine.media.permissionIntent() }.isFailure)
            val destination = PlaceCandidate("synthetic", "합성 장소", Coordinates(36.123456, 128.123456))
            for (command in MediaCommand.entries) {
                val ready = PolicyDecision.Ready(destination, command == MediaCommand.RESUME, mediaCommand = command)
                assertNull(engine.preflight(ready))
            }
            val request = runBlocking { engine.preparePlan(ToolPlan(listOf(Action.Navigate("집"), Action.MediaResume))) }
            assertNull(request.failure)
            assertIsReadyWithOriginalGoal(request.decision)
        }
        assertTrue(Intents.getIntents().none { it.data?.scheme == "nmap" || it.action == "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS" })
    }
    private fun assertIsReadyWithOriginalGoal(decision: PolicyDecision) {
        assertTrue(decision is PolicyDecision.Ready)
        decision as PolicyDecision.Ready
        assertEquals("집", decision.navigationGoal)
        assertEquals(MediaCommand.RESUME, decision.mediaCommand)
    }

    @Test fun automaticEntryStillListensAndClosesAfterOpeningClock() {
        val clock = "com.google.android.deskclock"
        context.startActivity(checkNotNull(context.packageManager.getLaunchIntentForPackage(clock)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { assertEquals(clock, device.currentPackageName) }
        val clockRequests = { Intents.getIntents().count { it.action == Intent.ACTION_MAIN && it.component?.packageName == clock } }
        val before = clockRequests()
        val fake = FakeSpeech()
        graph.speechFactoryOverride = { fake }
        context.startActivity(Intent(context, VoiceInvocationActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await {
            assertNotNull(fake.listener)
            val activity = voice(); assertNotNull(activity)
            instrumentation.runOnMainSync {
                assertTrue(activity!!.findViewById<TextView>(R.id.invocation_status).text.toString().contains("듣는"))
            }
        }
        instrumentation.runOnMainSync { fake.listener!!.onFinal("시계 앱 열어줘") }
        await { assertEquals(clock, device.currentPackageName) }
        await { assertNull(voice()) }
        assertEquals(before + 1, clockRequests())
    }
}
