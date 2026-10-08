package dev.localphone.agent

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.localphone.agent.runtime.*
import dev.localphone.core.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class NativeInferenceBoundTest {
    @Test fun actualNativeDeadlineAndPreStartCancellationLeaveNoPartialPlan() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.applicationContext as AgentApplication
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("LocalPhoneAgent_API35", device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        graph.planner.close()
        val weight = graph.embeddedModels.extract(LocalModelId.QWEN3)
        val pointer = LlamaNative.load(weight.absolutePath, 4)
        val tools = ModelToolCatalog.phone.filter { it["name"] == "open_settings" }
        val prompt = ("<|im_start|>system\nCall provided functions. Return JSON {\"calls\":[{\"name\":\"function name\",\"arguments\":{}}]}. " +
            "Functions: ${Gson().toJson(tools)} /no_think<|im_end|>\n<|im_start|>user\nOpen Wi-Fi settings.<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n").toByteArray()
        val grammar = ToolJsonGrammar.compile(tools).toByteArray()
        try {
            LlamaNative.begin(pointer)
            val timed = runCatching { LlamaNative.infer(pointer, prompt, 96, grammar, 1) }
            assertTrue(timed.isFailure); assertTrue(timed.exceptionOrNull()?.message.orEmpty().contains("time limit"))
            LlamaNative.begin(pointer); LlamaNative.cancel(pointer)
            val cancelled = runCatching { LlamaNative.infer(pointer, prompt, 96, grammar, 45_000) }
            assertTrue(cancelled.isFailure); assertTrue(cancelled.exceptionOrNull()?.message.orEmpty().contains("cancelled"))
            // A fresh generation must recover; no completed-response cache or Android dispatch.
            LlamaNative.begin(pointer)
            val raw = LlamaNative.infer(pointer, prompt, 96, grammar, 45_000).toString(Charsets.UTF_8)
            assertEquals(listOf(Action.OpenSettings(SettingsPage.WIFI)), ToolPlanDecoder.decode(ModelToolOutput.decode(raw)).actions)
            val directory = File(graph.getExternalFilesDir(null), "voice-model-evidence").apply { mkdirs() }
            File(directory, "native-bound.json").writeText(Gson().toJson(mapOf("scope" to "owned x86_64 AVD, actual native Qwen",
                "deadline_error" to timed.exceptionOrNull()?.message, "pre_start_cancel_error" to cancelled.exceptionOrNull()?.message,
                "recovered_raw_native_output" to raw, "timing" to LlamaNative.metrics(pointer), "android_dispatch" to "NONE")))
        } finally { LlamaNative.unload(pointer) }
    }
}
