package dev.localphone.core

import kotlin.test.*

class ResourceUsageTest {
    @Test fun cpuUsesFullDeviceCapacityRatherThanOneCore() {
        assertEquals(25, UsageMath.appCpu(100, 4100, 1000, 3000, 8))
        assertEquals(100, UsageMath.appCpu(100, 16100, 1000, 3000, 8))
    }
    @Test fun resetOrInvalidIntervalsStayUnknown() {
        assertNull(UsageMath.appCpu(100, 90, 1000, 3000, 8))
        assertNull(UsageMath.appCpu(100, 100, 1000, 1000, 8))
        assertNull(UsageMath.appCpu(100, 100, 1000, 3000, 0))
    }
    @Test fun cpuCountsNeitherGuestTwiceNorIoWaitAsWork() {
        val before = UsageMath.cpuTicks("cpu 100 10 20 200 10 5 5 0 50 1")!!
        val after = UsageMath.cpuTicks("cpu 130 10 40 220 30 10 10 0 80 1")!!
        assertEquals(350, before.total)
        assertEquals(60, UsageMath.totalCpu(before, after))
    }
    @Test fun unavailableMetricsAreNotPresentedAsZero() {
        assertNull(UsageMath.cpuTicks(null)); assertNull(UsageMath.cpuTicks("cpu unavailable"))
        assertNull(UsageMath.totalCpu(null, UsageMath.CpuTicks(10, 4)))
        assertTrue(ResourceUsage().totalLine.contains("CPU —"))
        assertFalse(ResourceUsage().aiLine.contains("0%"))
    }
    @Test fun disablingBothUsageRowsDisablesSamplingIndependentlyOfTextRows() {
        assertFalse(DisplayOptions(goal=true, task=true, total=false, ai=false).needsSampling)
        assertTrue(DisplayOptions(goal=false, task=false, total=true, ai=false).needsSampling)
        assertTrue(DisplayOptions(goal=false, task=false, total=false, ai=true).needsSampling)
    }
    @Test fun memoryStaysCompactEnoughForOneLine() {
        val reading = ResourceUsage(45, 8_589_934_592, 12_884_901_888, 25, 1_610_612_736)
        assertEquals("전체 CPU 45% · RAM 8.0G/12.0G", reading.totalLine)
        assertEquals("AI 앱 CPU 25% · RAM 1.5G", reading.aiLine)
    }
}
