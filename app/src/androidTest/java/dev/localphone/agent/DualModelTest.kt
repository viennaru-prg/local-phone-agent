package dev.localphone.agent

import android.content.Intent
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import com.google.gson.Gson
import dev.localphone.agent.runtime.*
import dev.localphone.core.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** REAL bundled inference on owned Android only. Text inputs; no human-microphone or S25 claim. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DualModelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private val evidence = linkedMapOf<String, Any>("scope" to "task-owned Android API35 x86_64 AVD; text inputs, REAL native model inference",
        "physical_s25" to "NOT_VERIFIED", "microphone_stt" to "NOT_VERIFIED", "backend" to "CPU", "threads" to 4,
        "battery_temperature" to "NOT_APPLICABLE_AVD", "version" to BuildConfig.VERSION_NAME)
    private fun save() {
        val directory = File(context.getExternalFilesDir(null), "model-evidence").apply { mkdirs() }
        val file = File(directory, "dual-model-validation.json")
        @Suppress("UNCHECKED_CAST")
        val existing = if (file.isFile) Gson().fromJson(file.readText(), Map::class.java) as Map<String, Any> else emptyMap()
        file.writeText(Gson().toJson(existing + evidence))
    }
    @Before fun ownedAvdAndRealModelsOnly() {
        Assume.assumeTrue(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        Assume.assumeTrue(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "LocalPhoneAgent_API35")
        graph.agentModelEnabledOverride = true
        graph.planner.acceptGemmaTerms() // Controlled test consent; the developer's official download was authorized.
    }
    @After fun cleanup() { graph.agentModelEnabledOverride = null }
    @Test fun aBothApkAssetsAreActualPinnedWeights() {
        val models = LocalModelId.entries.map { id ->
            val info = graph.embeddedModels.info(id)
            assertTrue(graph.embeddedModels.installed(id))
            val digest = MessageDigest.getInstance("SHA-256"); var bytes = 0L
            context.assets.open(info.asset).use { stream ->
                val buffer = ByteArray(1048576)
                while (true) { val count = stream.read(buffer); if (count < 0) break; bytes += count; digest.update(buffer, 0, count) }
            }
            assertEquals(info.bytes, bytes)
            assertEquals(info.sha256, digest.digest().joinToString("") { "%02x".format(it) })
            info
        }
        evidence["embedded_weight_verification"] = models
        save()
    }
    private fun memory() = mapOf("pss_kib" to Debug.getPss(), "proc_rss" to File("/proc/self/status").readLines().filter {
        it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }.joinToString("; "))
    @Test fun aFirstFunctionGemmaActualFormatDiagnostic() = runBlocking {
        graph.planner.close()
        val adapter = FunctionGemmaPlanner(graph.embeddedModels.info(LocalModelId.FUNCTIONGEMMA), File(graph.noBackupFilesDir, "model_cache/functiongemma"))
        try {
            val coldStarted = SystemClock.elapsedRealtime()
            val weight = graph.embeddedModels.extract(LocalModelId.FUNCTIONGEMMA)
            val extractMs = SystemClock.elapsedRealtime() - coldStarted
            val nativeStarted = SystemClock.elapsedRealtime()
            adapter.load(weight)
            evidence["functiongemma_first_install_cold_load"] = mapOf("extraction_ms" to extractMs,
                "native_load_ms" to SystemClock.elapsedRealtime() - nativeStarted, "memory" to memory())
            val probes = listOf("Open Clock." to "open_app", "Open Wi-Fi settings." to "open_settings",
                "Set a timer for 30 seconds." to "set_timer", "Navigate to Seoul." to "navigate")
            val responses = probes.map { (command, function) ->
                mapOf("input" to command, "response" to adapter.infer("Call the requested function.", command,
                    ModelToolCatalog.phone.filter { it["name"] == function }, 96))
            }
            evidence["functiongemma_format_probes"] = responses; save()
            assertTrue("No common Tool Plan from real model: $responses", responses.any {
                ToolPlanDecoder.decode((it["response"] as ModelResponse).calls).actions.isNotEmpty() })
        } finally { adapter.unload() }
    }
    private fun semantic(command: String, plan: ToolPlan): Boolean = when {
        command.contains("네이버지도 켜") -> plan.actions.singleOrNull() == Action.OpenApp("네이버지도")
        command.contains("회사") -> plan.actions.filterIsInstance<Action.Navigate>().singleOrNull()?.destination?.let(PlaceSlots::slotFor) == "office"
        command.contains("가면서") -> plan.actions.any { it is Action.Navigate && PlaceSlots.slotFor(it.destination) == "home" } && Action.MediaResume in plan.actions
        command == "음악 재생해" -> plan.actions == listOf(Action.MediaResume)
        command.contains("유튜브") -> plan.actions.filterIsInstance<Action.AppTask>().singleOrNull()?.let {
            it.appName == "유튜브" && PlaceText.normalize(it.goal) in setOf(PlaceText.normalize(command), PlaceText.normalize("노래 검색해")) } == true
        command.contains("저장") || command.contains("알림") -> plan.actions.filterIsInstance<Action.AppTask>().any { PlaceText.normalize(it.goal) == PlaceText.normalize(command) }
        else -> plan.actions.filterIsInstance<Action.Navigate>().singleOrNull()?.destination?.let(PlaceSlots::slotFor) == "home"
    }
    @Test fun bRealKoreanABAndSwitchingUseSelectedEngine() = runBlocking {
        val commands = listOf("네이버지도 켜줘", "회사로 가자", "집으로 네비 찍어줘", "음악 재생해", "현재 노래 저장해",
            "집으로 가면서 음악 틀어줘", "유튜브에서 노래 검색해", "최근 알림 읽어줘", "집으로 가자", "집에 가자", "지브로 가자")
        val results = mutableListOf<Map<String, Any?>>()
        val switches = mutableListOf<ModelSelectionResult>()
        var activity: MainActivity? = null
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        scenario.onActivity { activity = it }
        val engine = AgentEngine(checkNotNull(activity), graph)
        try {
            graph.planner.close()
            for (id in LocalModelId.entries) {
                val switched = graph.planner.select(id); switches += switched
                evidence["switches"] = switches; save()
                evidence["last_switch_probe"] = graph.planner.lastProbe ?: "no response"; save()
                assertTrue(switched.message, switched.success)
                assertEquals(id.key, graph.planner.loadedId)
                assertEquals(id, graph.planner.selected)
                val samples = Collections.synchronizedList(mutableListOf<Long>())
                val sampler = launch(Dispatchers.IO) { while (isActive) { samples.add(Debug.getPss()); delay(300) } }
                try {
                    for (command in commands) {
                        val started = SystemClock.elapsedRealtime(); val cpu = Process.getElapsedCpuTime()
                        val transcript = ResolvedTranscript(emptyList(), command, false, .99f, emptyList(), emptyList())
                        val request = engine.prepareSpeech(transcript, useModel = true)
                        val response = graph.planner.lastResponse
                        results += linkedMapOf("model" to id.key, "input" to command, "raw_model_output" to response?.raw,
                            "calls" to response?.calls, "format_error" to response?.formatError,
                            "inference_ms" to response?.inferenceMs, "pipeline_ms" to SystemClock.elapsedRealtime() - started,
                            "process_cpu_ms" to Process.getElapsedCpuTime() - cpu, "model_loaded" to graph.planner.loadedId,
                            "preparation" to graph.planner.lastLoad,
                            "interpretation" to request.interpretation, "effective_plan" to request.plan.toString(),
                            "model_plan_semantic_match" to (response != null && semantic(command,
                                PlanGrounding.validate(ToolPlanDecoder.decode(response.calls), command))),
                            "policy" to request.decision.toString(), "error" to graph.planner.lastError, "memory" to memory())
                        evidence["ab_results"] = results; save()
                        assertEquals(id.key, graph.planner.loadedId)
                        assertNotNull("No actual ${id.key} generation: ${graph.planner.lastError}", response)
                        if (request.interpretation.startsWith("MODEL_INFERENCE")) assertTrue(request.interpretation.endsWith(id.key))
                    }
                } finally { sampler.cancelAndJoin() }
                evidence["${id.key}_observed_peak_pss_kib"] = samples.maxOrNull() ?: Debug.getPss()
                save()
            }
            // Same model output -> common policy -> actual Android launcher Intent -> foreground evidence.
            val request = engine.prepareSpeech(ResolvedTranscript(emptyList(), "네이버지도 켜줘", false, .99f, emptyList(), emptyList()), true)
            assertEquals("MODEL_INFERENCE:qwen3", request.interpretation)
            val ready = request.decision as PolicyDecision.Ready
            val outcome = engine.execute(ready)
            assertNull(outcome.failure)
            withTimeout(5000) { while (device.currentPackageName != NaverLinks.PACKAGE) delay(100) }
            evidence["selected_model_android_intent"] = mapOf("interpretation" to request.interpretation,
                "foreground_package" to device.currentPackageName, "evidence" to outcome.evidence,
                "map_app" to "controlled Naver fixture, not real NAVER Map on S25")
            switches += graph.planner.select(LocalModelId.FUNCTIONGEMMA)
            assertTrue(switches.last().success); assertEquals("functiongemma", graph.planner.loadedId)
            evidence["switches"] = switches; save()
        } finally { scenario.close(); graph.planner.close() }
    }
    @Test fun cRealNativeCancellationFinishesBeforeMemoryIsReleased() = runBlocking {
        assertTrue(graph.planner.select(LocalModelId.QWEN3).success)
        val job = launch(Dispatchers.Default) { graph.planner.plan("집으로 가면서 음악 틀어줘") }
        delay(600)
        evidence["cancellation_started"] = mapOf("job_active" to job.isActive, "model_error" to graph.planner.lastError,
            "response" to (graph.planner.lastResponse?.raw ?: "no response")); save()
        val started = SystemClock.elapsedRealtime()
        job.cancel(); graph.planner.cancel()
        withTimeout(6000) { job.join() }
        assertTrue(job.isCancelled)
        val cancellationMs = SystemClock.elapsedRealtime() - started
        val next = graph.planner.plan("네이버지도 켜줘")
        assertEquals(listOf(Action.OpenApp("네이버지도")), next.actions)
        evidence["native_cancellation"] = mapOf("join_ms" to cancellationMs, "next_real_plan" to next.toString())
        save(); graph.planner.close()
    }
    @Test fun dBusySwitchRefusesAndRecreatedManagerRestoresSelection() = runBlocking {
        assertTrue(graph.planner.select(LocalModelId.QWEN3).success)
        assertTrue(graph.invocationArbiter.acquire("model-switch-test"))
        try {
            assertFalse(graph.planner.select(LocalModelId.FUNCTIONGEMMA).success)
            assertEquals("qwen3", graph.planner.loadedId)
        } finally { graph.invocationArbiter.release("model-switch-test") }
        graph.planner.close()
        val restored = LocalAgentModels(graph.embeddedModels, graph.settings, File(graph.noBackupFilesDir, "model_cache")) { false }
        assertEquals(LocalModelId.QWEN3, restored.selected)
        try { assertTrue(restored.prepare().available); assertEquals("qwen3", restored.loadedId) }
        finally { restored.close() }
        evidence["selection_persistence"] = "PASS encrypted setting and manager reconstruction; requires separate host process restart verification"
        save()
    }
    @Test fun eIdleEvictionStopsNativeResidencyAndInference() = runBlocking {
        assertTrue(graph.planner.select(LocalModelId.FUNCTIONGEMMA).success)
        graph.planner.releaseAfterIdle()
        delay(32_000)
        assertNull(graph.planner.loadedId)
        val cpu = Process.getElapsedCpuTime(); delay(2000)
        evidence["idle_after_eviction"] = mapOf("process_cpu_ms_over_2s" to Process.getElapsedCpuTime() - cpu, "memory" to memory())
        // Leave Qwen selection for the host restart verification; runtime is unloaded.
        assertTrue(graph.planner.select(LocalModelId.QWEN3).success); graph.planner.close()
        evidence["selected_before_host_restart"] = graph.planner.selected.key
        save()
    }
    @Test fun fFailedSwitchRestoresRealPreviousEngine() = runBlocking {
        assertTrue(graph.planner.select(LocalModelId.QWEN3).success)
        val before = memory()
        graph.planner.beforeModelLoadOverride = { id ->
            if (id == LocalModelId.FUNCTIONGEMMA) error("Injected initialization failure; no fake inference output")
        }
        try {
            val switched = graph.planner.select(LocalModelId.FUNCTIONGEMMA)
            assertFalse(switched.success)
            assertEquals(LocalModelId.QWEN3, graph.planner.selected)
            assertEquals("qwen3", graph.planner.loadedId)
            assertEquals("qwen3", graph.settings.get("agent_model"))
            val actualProbe = checkNotNull(graph.planner.lastProbe)
            assertEquals(listOf(Action.OpenSettings(SettingsPage.WIFI)), ToolPlanDecoder.decode(actualProbe.calls).actions)
            evidence["failed_switch_rollback"] = mapOf("injected_fault" to "initialization failure after old native unload",
                "selection" to switched, "real_restored_qwen_probe" to actualProbe, "before" to before, "after" to memory())
            save()
        } finally { graph.planner.beforeModelLoadOverride = null; graph.planner.close() }
    }
    @Test fun gInterruptedAndCorruptPrivateCopiesRecoverFromApk() = runBlocking {
        graph.planner.close()
        val id = LocalModelId.QWEN3
        val destination = graph.embeddedModels.extractedFile(id)
        assertTrue(destination.isFile && destination.delete()) // App-owned derived cache, never APK or user data.
        val part = File(destination.parentFile, destination.name + ".part")
        val copy = launch(Dispatchers.IO) { graph.embeddedModels.extract(id) }
        withTimeout(10_000) { while (!part.isFile || part.length() < 8 * 1024 * 1024) delay(5) }
        copy.cancelAndJoin()
        assertTrue(copy.isCancelled); assertFalse(part.exists()); assertFalse(destination.exists())
        val recovered = graph.embeddedModels.extract(id)
        RandomAccessFile(recovered, "rw").use { file ->
            file.seek(1024); val byte = file.read(); file.seek(1024); file.write(byte xor 255); file.fd.sync()
        }
        // A new repository performs the same checksum check as a new app process.
        val repaired = EmbeddedModelRepository(context).extract(id)
        val hash = MessageDigest.getInstance("SHA-256")
        repaired.inputStream().use { input ->
            val buffer = ByteArray(1048576)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        val actualSha = hash.digest().joinToString("") { "%02x".format(it) }
        assertEquals(graph.embeddedModels.info(id).sha256, actualSha)
        assertFalse(part.exists())
        fun bytes(directory: File) = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val systemStats = runCatching<Map<String, Any>> {
            val service = context.getSystemService(android.app.usage.StorageStatsManager::class.java)
            val stats = service.queryStatsForPackage(android.os.storage.StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
            mapOf("app_bytes" to stats.appBytes, "data_bytes" to stats.dataBytes, "cache_bytes" to stats.cacheBytes)
        }.getOrElse { mapOf("unavailable" to (it.message ?: it.javaClass.simpleName)) }
        evidence["apk_extraction_recovery"] = mapOf("cancelled_stream" to "PASS", "part_file_remaining" to false,
            "corrupted_copy_replaced" to "PASS", "repaired_sha256" to actualSha)
        evidence["installed_storage"] = mapOf("android_storage_stats" to systemStats,
            "base_apk_bytes" to File(context.applicationInfo.sourceDir).length(), "private_no_backup_bytes" to bytes(context.noBackupFilesDir),
            "private_model_weights_bytes" to bytes(destination.parentFile!!), "private_cache_bytes" to bytes(context.cacheDir),
            "native_libraries_bytes" to bytes(File(context.applicationInfo.nativeLibraryDir)))
        save()
    }
    @Test fun hRealOfflineKoreanSpeechUsesSelectedQwenAndActualClock() = runBlocking<Unit> {
        assertTrue(graph.planner.select(LocalModelId.QWEN3).success)
        val wave = instrumentation.context.assets.open("synthetic-alarm-ko.wav").use { it.readBytes() }
        assertEquals("RIFF", String(wave, 0, 4, Charsets.US_ASCII))
        var position = 12; var pcm: ByteArray? = null
        while (position + 8 <= wave.size) {
            val size = ByteBuffer.wrap(wave, position + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(size >= 0 && position + 8 + size <= wave.size)
            if (String(wave, position, 4, Charsets.US_ASCII) == "data") {
                pcm = wave.copyOfRange(position + 8, position + 8 + size); break
            }
            position += 8 + size + size % 2
        }
        val audio = checkNotNull(pcm)
        val recognition = CompletableDeferred<SpeechRecognitionResult>()
        val speech = LocalSpeechInput(graph.speechModel) { object : PcmSource {
            var offset = 0; var stopped = false
            override fun start() = Unit
            override fun read(buffer: ByteArray): Int {
                if (stopped || offset >= audio.size) return -1
                val count = minOf(buffer.size, audio.size - offset)
                audio.copyInto(buffer, 0, offset, offset + count); offset += count; return count
            }
            override fun stop() { stopped = true }
            override fun close() = stop()
        } }
        var activity: MainActivity? = null
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        scenario.onActivity { activity = it }
        try {
            speech.start(object : SpeechInput.Listener {
                override fun onListening() = Unit
                override fun onLevel(level: Float) = Unit
                override fun onPartial(text: String) = Unit
                override fun onFinal(text: String) { error("Expected structured real recognition") }
                override fun onRecognition(result: SpeechRecognitionResult) { recognition.complete(result) }
                override fun onError(message: String) { recognition.completeExceptionally(IllegalStateException(message)) }
            })
            val result = withTimeout(60_000) { recognition.await() }
            assertTrue(result.onDevice)
            val engine = AgentEngine(checkNotNull(activity), graph)
            val transcript = graph.speechDiagnostics.resolve(result, engine.speechContext())
            val request = withTimeout(90_000) { engine.prepareSpeech(transcript, true) }
            evidence["offline_speech_model_tool"] = mapOf("source" to "synthetic Korean PCM; REAL sherpa-onnx decoder, not microphone",
                "recognition" to result, "resolved" to transcript, "interpretation" to request.interpretation,
                "model_output" to (graph.planner.lastResponse ?: "no response"), "plan" to request.plan.toString())
            save()
            assertEquals("MODEL_INFERENCE:qwen3", request.interpretation)
            assertEquals(listOf(Action.SetAlarm(7, 0)), request.plan.actions)
            val outcome = engine.execute(request.decision as PolicyDecision.Ready)
            assertNull(outcome.failure)
            withTimeout(5000) { while (device.currentPackageName != "com.google.android.deskclock") delay(100) }
            evidence["offline_speech_model_tool_execution"] = mapOf("evidence" to outcome.evidence, "foreground" to device.currentPackageName)
            save()
        } finally {
            speech.close(); scenario.close(); graph.planner.close()
            device.executeShellCommand("pm clear com.google.android.deskclock") // Remove the synthetic alarm only on the owned AVD.
        }
    }
}
