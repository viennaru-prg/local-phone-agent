package dev.localphone.agent

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import dev.localphone.core.ResourceUsage
import dev.localphone.core.UsageMath
import java.io.File

/** Two tiny proc reads and one Android memory query, only for enabled rows. No PSS scan or shell. */
internal class UsageSampler(context: Context) {
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val memory = ActivityManager.MemoryInfo()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var cpu: Long? = null
    private var time = 0L
    private var ticks: UsageMath.CpuTicks? = null
    var samples = 0; private set
    var elapsedNs = 0L; private set

    fun read(total: Boolean, ai: Boolean): ResourceUsage {
        val started = SystemClock.elapsedRealtimeNanos()
        var totalCpu: Int? = null; var appCpu: Int? = null
        var used: Long? = null; var all: Long? = null; var resident: Long? = null
        if (total) {
            val next = if (canReadTotalCpu) runCatching {
                File("/proc/stat").bufferedReader().use { UsageMath.cpuTicks(it.readLine()) }
            }.getOrElse { canReadTotalCpu = false; null } else null
            totalCpu = UsageMath.totalCpu(ticks, next); ticks = next
            runCatching {
                activity.getMemoryInfo(memory)
                if (memory.totalMem > 0) {
                    all = memory.totalMem; used = (memory.totalMem - memory.availMem).coerceIn(0, memory.totalMem)
                }
            }
        }
        if (ai) {
            val now = SystemClock.elapsedRealtime(); val next = Process.getElapsedCpuTime()
            appCpu = cpu?.let { UsageMath.appCpu(it, next, time, now, cores) }; cpu = next; time = now
            resident = runCatching {
                File("/proc/self/status").bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()?.substringBefore(' ')
                        ?.toLongOrNull()?.times(1024)
                }
            }.getOrNull()
        }
        samples++; elapsedNs += SystemClock.elapsedRealtimeNanos() - started
        return ResourceUsage(totalCpu, used, all, appCpu, resident)
    }

    companion object { @Volatile private var canReadTotalCpu = true }
}
