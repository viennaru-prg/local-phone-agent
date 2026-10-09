package dev.localphone.agent

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphone.core.DisplayOptions
import dev.localphone.core.ResourceUsage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunDisplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun liveReadUsesActualMemoryAndHandlesUnavailableSystemCpu() {
        val reader = UsageSampler(context)
        val readings = (1..8).map { reader.read(true, true) }
        val last = readings.last()
        assertTrue(last.totalRam!! > 0); assertTrue(last.usedRam!! in 0..last.totalRam!!)
        assertTrue(last.appRam!! > 0)
        assertTrue(last.appCpu == null || last.appCpu!! in 0..100)
        assertTrue(last.totalCpu == null || last.totalCpu!! in 0..100)
        android.util.Log.i("AgentDisplayTest", "samples=${reader.samples} meanReadUs=${reader.elapsedNs / reader.samples / 1000} totalRam=${last.totalRam} appRam=${last.appRam} totalCpu=${last.totalCpu}")
    }

    @Test fun everyRowCanBeHiddenWithoutLosingTheOriginalGoal() {
        instrumentation.runOnMainSync {
            val view = RunStatusView(context)
            val goal = "회사로 안내하고 음악을 틀어줘"
            val usage = ResourceUsage(appCpu=12, appRam=268_435_456)
            view.render(goal, "목적지 찾는 중…", usage, DisplayOptions())
            assertEquals(4, view.childCount)
            assertEquals("명령: $goal", (view.getChildAt(0) as TextView).text.toString())
            view.render(goal, "음악 조작 중…", usage, DisplayOptions())
            assertEquals("명령: $goal", (view.getChildAt(0) as TextView).text.toString())
            assertEquals("작업: 음악 조작 중…", (view.getChildAt(1) as TextView).text.toString())
            (0 until 4).forEach { hidden ->
                view.render(goal, "작업 중…", usage, DisplayOptions(hidden!=0, hidden!=1, hidden!=2, hidden!=3))
                assertEquals(View.GONE, view.getChildAt(hidden).visibility)
            }
            (0 until 4).forEach { assertEquals(1, (view.getChildAt(it) as TextView).maxLines) }
            view.render(goal, "작업 중…", usage, DisplayOptions(false,false,false,false))
            assertEquals(View.GONE, view.visibility)
            view.render(goal, "잠금을 풀어 주세요", usage, DisplayOptions(false,false,false,false), important=true)
            assertEquals(View.VISIBLE, view.getChildAt(1).visibility)
        }
    }

    @Test fun disabledUsageDoesNoPollingAndStopLeavesNoIdleUpdates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val monitor = UsageMonitor(context, scope)
        val updates = java.util.concurrent.atomic.AtomicInteger()
        try {
            withContext(Dispatchers.Main) { monitor.start(DisplayOptions(total=false,ai=false)) { updates.incrementAndGet() } }
            delay(100)
            assertFalse(monitor.running); assertEquals(0, updates.get())
            withContext(Dispatchers.Main) { monitor.start(DisplayOptions(total=false,ai=true)) { updates.incrementAndGet() } }
            val deadline = SystemClock.elapsedRealtime() + 4000
            while (updates.get() == 0 && SystemClock.elapsedRealtime() < deadline) delay(50)
            assertTrue(monitor.running); assertTrue(updates.get() > 0)
            withContext(Dispatchers.Main) { monitor.stop() }
            val stopped = updates.get()
            delay(UsageMonitor.INTERVAL_MS + 100)
            assertFalse(monitor.running); assertEquals(stopped, updates.get())
        } finally { withContext(Dispatchers.Main) { monitor.stop(); scope.cancel() } }
    }
}
