package dev.localphone.agent

import android.content.Context
import android.util.Log
import dev.localphone.core.DisplayOptions
import dev.localphone.core.ResourceUsage
import kotlinx.coroutines.*

/** One coroutine while a task is active; stop cancels sampling before its next read. */
internal class UsageMonitor(private val context: Context, private val scope: CoroutineScope) {
    private var job: Job? = null
    private var sampler: UsageSampler? = null
    val running get() = job?.isActive == true
    val samples get() = sampler?.samples ?: 0

    fun start(options: DisplayOptions, update: (ResourceUsage) -> Unit) {
        stop()
        if (!options.needsSampling) return
        val reader = UsageSampler(context).also { sampler = it }
        job = scope.launch {
            // CPU is a difference between two reads: the second comes quickly so a short command shows it too.
            var first = true
            while (isActive) {
                val usage = withContext(Dispatchers.IO) { reader.read(options.total, options.ai) }
                ensureActive(); update(usage)
                delay(if (first) FIRST_DELTA_MS else INTERVAL_MS); first = false
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        sampler?.takeIf { it.samples > 0 }?.let {
            Log.i("AgentUsage", "stopped samples=${it.samples} meanReadUs=${it.elapsedNs / it.samples / 1000}")
        }
        sampler = null
    }

    companion object { const val INTERVAL_MS = 2_000L; const val FIRST_DELTA_MS = 500L }
}
