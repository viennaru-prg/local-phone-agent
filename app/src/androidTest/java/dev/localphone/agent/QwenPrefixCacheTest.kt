package dev.localphone.agent

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.localphone.agent.runtime.*
import dev.localphone.core.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class QwenPrefixCacheTest {
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(262144)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Test fun realInputPrefixRestoresFreshInferenceAndCorruptionFallsBackToRealPreparation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.applicationContext as AgentApplication
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("LocalPhoneAgent_API35", device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        graph.agentModelEnabledOverride = null
        assertTrue(graph.planner.select(LocalModelId.QWEN3).success)
        val directory = File(graph.noBackupFilesDir, "model_cache/qwen3-input-prefix")
        val snapshot = File(directory, "phone-input.state")
        assertTrue(snapshot.isFile); assertTrue(snapshot.length() in 1..128L * 1024 * 1024)
        val original = hash(snapshot)
        try {
            graph.planner.close()
            assertTrue(graph.planner.prepare().available)
            val restored = graph.planner.lastLoadTimings!!
            assertTrue(restored.inputPrefixRestored); assertEquals(0L, restored.probeMs)
            assertNull(graph.planner.lastProbe) // No cached health reply presented as new generation.
            val plan = graph.planner.plan("오전 7시 알람 맞춰줘")
            assertEquals(listOf(Action.SetAlarm(7, 0)), plan.actions)
            val response = graph.planner.lastResponse!!
            assertTrue(response.timing!!.reusedInputTokens > 250); assertTrue(response.timing.outputTokens > 0)
            assertEquals(original, hash(snapshot)) // User KV/replies never overwrite the constant input cache.
            graph.planner.close()
            RandomAccessFile(snapshot, "rw").use { file -> val first = file.readByte(); file.seek(0); file.writeByte(first.toInt() xor 1) }
            assertTrue(graph.planner.prepare().available)
            val recovered = graph.planner.lastLoadTimings!!
            assertFalse(recovered.inputPrefixRestored); assertTrue(recovered.probeMs > 0)
            assertEquals(listOf(Action.OpenSettings(SettingsPage.WIFI)), ToolPlanDecoder.decode(graph.planner.lastProbe!!.calls).actions)
            assertEquals(listOf(Action.OpenSettings(SettingsPage.WIFI)), graph.planner.plan("와이파이 설정 열어줘").actions)
            val evidence = File(graph.getExternalFilesDir(null), "voice-model-evidence").apply { mkdirs() }
            File(evidence, "qwen-input-cache.json").writeText(Gson().toJson(mapOf("scope" to "owned AVD, actual native Qwen, no Android dispatch",
                "restored_load" to restored, "fresh_command" to response, "corrupt_cache_recovery" to recovered,
                "constant_input_state_bytes" to snapshot.length(), "user_inference_did_not_change_snapshot" to true)))
        } finally { graph.planner.close() }
    }
}
