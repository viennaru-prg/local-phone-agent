package dev.localphone.core

import kotlin.math.roundToInt

data class DisplayOptions(val goal: Boolean = true, val task: Boolean = true, val total: Boolean = true, val ai: Boolean = true) {
    val needsSampling get() = total || ai
}

/** Unknown values stay null; app CPU is normalized to the device's full CPU capacity. */
data class ResourceUsage(
    val totalCpu: Int? = null, val usedRam: Long? = null, val totalRam: Long? = null,
    val appCpu: Int? = null, val appRam: Long? = null,
) {
    val totalLine get() = "전체 CPU ${percent(totalCpu)} · RAM ${usedRam?.let(::memory) ?: "—"}/${totalRam?.let(::memory) ?: "—"}"
    val aiLine get() = "AI 앱 CPU ${percent(appCpu)} · RAM ${appRam?.let(::memory) ?: "—"}"
    private fun percent(value: Int?) = value?.let { "$it%" } ?: "—"
    private fun memory(bytes: Long): String = if (bytes < 1_073_741_824L) "${bytes / 1_048_576}M" else {
        val tenths = (bytes / 107_374_182.4).roundToInt()
        "${tenths / 10}.${tenths % 10}G"
    }
}

object UsageMath {
    fun appCpu(beforeCpu: Long, cpu: Long, beforeTime: Long, time: Long, cores: Int): Int? {
        if (cores < 1 || time <= beforeTime || cpu < beforeCpu) return null
        return ((cpu - beforeCpu).toDouble() * 100 / (time - beforeTime) / cores).roundToInt().coerceIn(0, 100)
    }

    data class CpuTicks(val total: Long, val idle: Long)
    /** guest counters are already included in user/nice; never count them twice. */
    fun cpuTicks(line: String?): CpuTicks? {
        val fields = line?.trim()?.split(Regex("\\s+")) ?: return null
        if (fields.firstOrNull() != "cpu" || fields.size < 5) return null
        val ticks = fields.drop(1).take(8).map { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        return CpuTicks(ticks.sum(), ticks[3] + (ticks.getOrNull(4) ?: 0))
    }
    fun totalCpu(before: CpuTicks?, now: CpuTicks?): Int? {
        if (before == null || now == null || now.total <= before.total || now.idle < before.idle) return null
        val delta = now.total - before.total
        return ((delta - (now.idle - before.idle)).toDouble() * 100 / delta).roundToInt().coerceIn(0, 100)
    }
}
