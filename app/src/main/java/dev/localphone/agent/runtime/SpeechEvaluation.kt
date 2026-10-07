package dev.localphone.agent.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.net.ConnectivityManager
import android.os.*
import android.provider.Settings
import dev.localphone.agent.AgentApplication
import dev.localphone.core.*
import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlin.coroutines.resume
import kotlinx.coroutines.*

data class EvaluationNetwork(val airplaneMode: Boolean, val activeNetwork: Boolean) {
    val offline get() = airplaneMode && !activeNetwork
}
fun evaluationNetwork(context: Context) = EvaluationNetwork(
    Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
    context.getSystemService(ConnectivityManager::class.java).activeNetwork != null)
data class EvaluationPerformance(val appPssKb: Int, val processCpuMs: Long, val charging: Boolean,
    val temperatureC: Float?, val currentMicroamps: Int?, val energyNanowattHours: Long?)
fun evaluationPerformance(context: Context): EvaluationPerformance {
    val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val manager = context.getSystemService(BatteryManager::class.java)
    return EvaluationPerformance(memory.totalPss, Process.getElapsedCpuTime(), (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
        battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeUnless { it == Int.MIN_VALUE }?.div(10f),
        manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeUnless { it == Int.MIN_VALUE },
        manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER).takeUnless { it == Long.MIN_VALUE })
}
data class EvaluationAudio(val pcm: ByteArray, val captureMs: Long, val source: String)

/** Explicit evaluation-button capture only. Production native STT does not use this recorder. */
class SpeechEvaluationCapture(private val context: Context, private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION) {
    private val stopped = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    fun stop() { stopped.set(true); runCatching { recorder?.stop() } }
    @SuppressLint("MissingPermission")
    suspend fun capture(onReady: () -> Unit, onLevel: (Float) -> Unit): EvaluationAudio = withContext(Dispatchers.IO) {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0)
        val audio = AudioRecord(audioSource, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
        val bytes = ByteArrayOutputStream()
        val started = SystemClock.elapsedRealtime(); var lastVoice = started; var spoken = false
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            recorder = audio; audio.startRecording(); check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            withContext(Dispatchers.Main) { onReady() }
            val buffer = ByteArray(1600)
            while (!stopped.get() && bytes.size() < 576_000) {
                ensureActive()
                val count = runCatching { audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) }.getOrElse { if (stopped.get()) 0 else throw it }
                if (count <= 0) { if (stopped.get()) break else error("PCM_CAPTURE_FAILED") }
                bytes.write(buffer, 0, count)
                var sum = 0.0
                for (i in 0 until count - 1 step 2) {
                    val value = ((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort().toDouble() / 32768
                    sum += value * value
                }
                val rms = sqrt(sum / maxOf(1, count / 2)).toFloat()
                val now = SystemClock.elapsedRealtime()
                if (rms > .012f) { spoken = true; lastVoice = now }
                withContext(Dispatchers.Main) { onLevel((rms * 15).coerceIn(0f, 1f)) }
                if ((spoken && now - lastVoice >= 1400) || (!spoken && now - started >= 8000)) break
            }
            check(bytes.size() >= 3200) { "NO_RECORDED_AUDIO" }
            EvaluationAudio(bytes.toByteArray(), SystemClock.elapsedRealtime() - started, when (audioSource) {
                MediaRecorder.AudioSource.MIC -> "MIC"; MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"; else -> "VOICE_RECOGNITION"
            })
        } finally { runCatching { audio.stop() }; audio.release(); recorder = null }
    }
}

data class EvaluationVariant(val name: String, val recognition: SpeechRecognitionResult?, val resolved: ResolvedTranscript?,
    val selectedText: String, val measure: SpeechBenchmarkMeasure, val wallMs: Long, val error: String? = null)
data class SpeechEvaluationRecord(val case: SpeechBenchmarkCase, val expectedText: String, val recordedAt: Long,
    val environment: String, val focus: String, val audioSource: String, val audioSha256: String, val audioDurationMs: Long,
    val networkBefore: EvaluationNetwork, val networkAfter: EvaluationNetwork, val performanceBefore: EvaluationPerformance,
    val performanceAfter: EvaluationPerformance, val variants: List<EvaluationVariant>, val rawAudioSaved: Boolean,
    val realUserSpeech: Boolean = true, val execution: String = "NOT_RUN_STT_EVALUATION_ONLY",
    val appVersion: String = dev.localphone.agent.BuildConfig.VERSION_NAME,
    val appVersionCode: Int = dev.localphone.agent.BuildConfig.VERSION_CODE, val appApkSha256: String = "")

private class EvaluationPcmSource(private val bytes: ByteArray) : PcmSource {
    private var offset = 0; private var ended = false
    override fun start() = Unit
    override fun read(buffer: ByteArray): Int {
        if (ended || offset >= bytes.size) return -1
        val length = minOf(buffer.size, bytes.size - offset); bytes.copyInto(buffer, 0, offset, offset + length); offset += length
        return length
    }
    override fun stop() { ended = true }
    override fun close() = stop()
}
fun evaluationReplaySource(pcm: ByteArray): ParcelFileDescriptor {
    val pipe = ParcelFileDescriptor.createPipe()
    Thread({ runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
        // Same leading/trailing padding for all replay variants. No microphone is used by this writer.
        val bytes = ByteArray(8000) + pcm + ByteArray(32_000)
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(3200, bytes.size - offset); output.write(bytes, offset, length); output.flush()
            offset += length; Thread.sleep(100)
        }
    } } }, "local-evaluation-replay").start()
    return pipe[0]
}
private suspend fun recognizeEvaluation(input: SpeechInput): SpeechRecognitionResult = withContext(Dispatchers.Main) {
    try { withTimeout(30_000) { suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { input.cancel() } }
        input.start(object : SpeechInput.Listener {
            override fun onListening() = Unit
            override fun onLevel(level: Float) = Unit
            override fun onPartial(text: String) = Unit
            override fun onFinal(text: String) { if (continuation.isActive) continuation.resume(SpeechRecognitionResult(listOf(
                SpeechHypothesis(text, null, 0)), engine = "legacy-evaluation", onDevice = true)) }
            override fun onRecognition(result: SpeechRecognitionResult) { if (continuation.isActive) continuation.resume(result) }
            override fun onError(message: String) { if (continuation.isActive) continuation.cancel(IllegalStateException(message)) }
        })
    } } } finally { input.cancel() }
}

class SpeechEvaluator(private val graph: AgentApplication) {
    private val gson = Gson()
    private val apkSha by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        File(graph.applicationInfo.sourceDir).inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    val directory = File(graph.noBackupFilesDir, "stt-evaluation")
    fun records(): List<SpeechEvaluationRecord> = if (!directory.exists()) emptyList() else directory.listFiles().orEmpty()
        .filter { it.extension == "json" }.sortedBy { it.name }.mapNotNull {
            runCatching { gson.fromJson(it.readText(), SpeechEvaluationRecord::class.java) }.getOrNull()
        }
    fun clear() {
        check(directory.canonicalFile.parentFile == graph.noBackupFilesDir.canonicalFile)
        directory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
    }
    suspend fun evaluate(case: SpeechBenchmarkCase, reference: String, audio: EvaluationAudio, context: SpeechContext,
        environment: String, focus: String, saveAudio: Boolean, onProgress: (String) -> Unit): SpeechEvaluationRecord {
        require(audio.pcm.size <= 576_000 && reference.length in 1..1000)
        val before = evaluationNetwork(graph); val performanceBefore = evaluationPerformance(graph)
        val variants = mutableListOf<EvaluationVariant>()
        suspend fun run(name: String, input: SpeechInput, resolve: Boolean, scope: SpeechContext) {
            onProgress(name)
            val start = SystemClock.elapsedRealtime()
            try {
                val recognition = recognizeEvaluation(input)
                val resolved = if (resolve) ContextualTranscriptResolver().resolve(recognition, scope) else null
                val text = resolved?.selectedText ?: recognition.hypotheses.firstOrNull()?.text.orEmpty()
                variants += EvaluationVariant(name, recognition, resolved, text,
                    SpeechBenchmark.measure(reference, text, context, resolved?.requiresClarification == true), SystemClock.elapsedRealtime() - start)
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException && error.cause !is IllegalStateException) throw error
                variants += EvaluationVariant(name, null, null, "", SpeechBenchmark.measure(reference, "", context, true),
                    SystemClock.elapsedRealtime() - start, error.message ?: error.javaClass.simpleName)
            }
        }
        // Retain the original, already bundled model solely as an explicit comparison baseline.
        run("BASELINE_BUNDLED_TOP1", LocalSpeechInput(graph.speechModel, sourceFactory = { EvaluationPcmSource(ByteArray(8000) + audio.pcm + ByteArray(32_000)) }), false, context)
        if (Build.VERSION.SDK_INT >= 33 && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(graph)) {
            for (count in listOf(0, 12, 24)) {
                val vocabulary = context.copy(biasLimit = count).biasStrings()
                val input = OnDeviceSpeechInput(graph, vocabulary, replaySource = { evaluationReplaySource(audio.pcm) })
                run("NATIVE_BIAS_${count}_TOP1", input, false, context.copy(biasLimit = count))
                if (count == 24) {
                    val native = variants.last()
                    native.recognition?.let { recognition ->
                        val start = SystemClock.elapsedRealtime(); val resolved = ContextualTranscriptResolver().resolve(recognition, context)
                        variants += EvaluationVariant("NATIVE_BIAS_24_CONTEXT", recognition, resolved, resolved.selectedText,
                            SpeechBenchmark.measure(reference, resolved.selectedText, context, resolved.requiresClarification), native.wallMs + SystemClock.elapsedRealtime() - start)
                    }
                }
                delay(250)
            }
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(audio.pcm).joinToString("") { "%02x".format(it) }
        val record = SpeechEvaluationRecord(case, reference, System.currentTimeMillis(), environment, focus, audio.source,
            sha, audio.pcm.size / 32L, before, evaluationNetwork(graph), performanceBefore, evaluationPerformance(graph), variants, saveAudio,
            appApkSha256 = withContext(Dispatchers.IO) { apkSha })
        directory.mkdirs()
        val filename = "${record.recordedAt}-${case.id.filter { it.isLetterOrDigit() }}"
        if (saveAudio) {
            check(directory.listFiles().orEmpty().sumOf { it.length() } + audio.pcm.size < 32 * 1024 * 1024) { "평가 저장 공간이 가득 찼습니다. 기존 기록을 지워 주세요." }
            File(directory, "$filename.pcm").writeBytes(audio.pcm)
        }
        File(directory, "$filename.json").writeText(gson.toJson(record))
        return record
    }
    fun summary(): String {
        val stored = records()
        val current = stored.filter { it.realUserSpeech && it.appVersionCode == dev.localphone.agent.BuildConfig.VERSION_CODE }
        val latestHash = current.lastOrNull()?.appApkSha256
        val all = current.filter { it.appApkSha256 == latestHash }
        return buildString {
            appendLine("현재 버전·가장 최근 APK의 기록만 비교 (다른 빌드 ${stored.size - all.size}개 제외)")
            appendLine("실제 발화 ${all.size}개 / 서로 다른 문장 ${all.map { it.case.id }.distinct().size}개")
            appendLine("환경 ${all.groupingBy { it.environment }.eachCount()}; Focus ${all.groupingBy { it.focus }.eachCount()}; AudioSource ${all.groupingBy { it.audioSource }.eachCount()}")
            appendLine("비행기 모드·활성 네트워크 없음: ${all.count { it.networkBefore.offline && it.networkAfter.offline }}개")
            for (mode in all.flatMap { it.variants }.map { it.name }.distinct()) {
                val results = all.flatMap { it.variants }.filter { it.name == mode }
                fun metric(select: (SpeechBenchmarkMeasure) -> Boolean?): String {
                    val measured = results.mapNotNull { select(it.measure) }
                    return if (measured.isEmpty()) "미측정" else "${measured.count { it }}/${measured.size} (${(100f * measured.count { it } / measured.size).toInt()}%)"
                }
                appendLine("\n$mode (${results.size}개)")
                appendLine("Exact ${metric { it.exact }}; Semantic ${metric { it.semantic }}")
                appendLine("Intent ${metric { it.intent }}; Entity ${metric { it.entity }}; Tool plan ${metric { it.toolPlan }}")
                appendLine("Replay 평균 ${results.map { it.wallMs }.average().toInt()} ms; ASR 오류 ${results.count { it.error != null }}개")
            }
            appendLine("\n실제 앱 작업 성공률: 미측정 — 음성 평가에서는 실행하지 않음")
            appendLine("충전 중 기록 ${all.count { it.performanceBefore.charging }}개: 배터리 영향 A/B 판정 불가")
            appendLine("App PSS/CPU는 이 앱만 측정; native 서비스 CPU/RAM과 전체 배터리 비용은 별도 실측 필요")
            appendLine("Bias strings는 요청값이며 OS가 적용했는지는 동일 음성 A/B 결과로 평가")
        }
    }
}
