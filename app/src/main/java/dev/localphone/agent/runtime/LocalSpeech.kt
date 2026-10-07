package dev.localphone.agent.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.localphone.core.SpeechHypothesis
import dev.localphone.core.SpeechRecognitionResult
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

enum class SpeechError { NO_SPEECH, STT_FAILED, PERMISSION_REQUIRED }
data class SpeechFailure(val code: SpeechError, val message: String)
data class SpeechMetrics(val coldModel: Boolean, val modelLoadMs: Long, val captureMs: Long, val finalDecodeMs: Long,
                         val bufferedAudioBytes: Int, val engine: String = "sherpa-onnx-korean-local")
data class SpeechTiming(val startTimeoutMs: Int = 4500, val trailingSilenceMs: Int = 900, val maxUtteranceMs: Int = 18000) {
    init { require(startTimeoutMs in 3000..8000 && trailingSilenceMs in 700..1500 && maxUtteranceMs in 15000..20000) }
}

interface SpeechInput : AutoCloseable {
    interface Listener {
        fun onListening()
        fun onListeningAt(elapsedRealtimeMs: Long) = onListening()
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onRecognition(result: SpeechRecognitionResult) = onFinal(result.hypotheses.firstOrNull()?.text.orEmpty())
        fun onError(message: String)
        fun onSpeechStarted() {}
        fun onSpeechEnded() {}
        fun onSpeechStartedAt(elapsedRealtimeMs: Long) = onSpeechStarted()
        fun onSpeechEndedAt(elapsedRealtimeMs: Long) = onSpeechEnded()
        fun onMetrics(metrics: SpeechMetrics) {}
        fun onFailure(failure: SpeechFailure) = onError(failure.message)
    }
    fun start(listener: Listener)
    fun cancel()
    override fun close() = cancel()
}

/** Decoder is serialized. Capture runs independently, so model loading cannot lose initial speech. */
class SpeechModelRepository(private val context: Context) {
    private var model: OnlineRecognizer? = null
    private var modelTiming: SpeechTiming? = null
    private var generation = 0L
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "local-korean-decoder") }
    private val cleanup = Executors.newSingleThreadScheduledExecutor { Thread(it, "speech-idle-release") }
    private var releaseJob: ScheduledFuture<*>? = null
    fun enqueue(block: () -> Unit) { worker.execute(block) }
    @Synchronized fun isWarm(timing: SpeechTiming = SpeechTiming()) = model != null && modelTiming == timing
    @Synchronized fun get(timing: SpeechTiming = SpeechTiming()): OnlineRecognizer {
        generation++; releaseJob?.cancel(false)
        if (modelTiming == timing) model?.let { return it }
        model?.release(); model = null
        val base = "speech-model-ko"
        val config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$base/encoder-epoch-99-avg-1.int8.onnx", decoder = "$base/decoder-epoch-99-avg-1.onnx",
                    joiner = "$base/joiner-epoch-99-avg-1.int8.onnx",
                ), tokens = "$base/tokens.txt", numThreads = 2, modelType = "zipformer", provider = "cpu",
            ), endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, timing.startTimeoutMs / 1000f, 0f),
                rule2 = EndpointRule(true, timing.trailingSilenceMs / 1000f, 0f),
                rule3 = EndpointRule(false, 0f, timing.maxUtteranceMs / 1000f),
            ), enableEndpoint = true,
        )
        return OnlineRecognizer(context.assets, config).also { model = it; modelTiming = timing }
    }
    // A provisional 60 s STT cache, not a service. S25 memory/battery tuning remains a device test.
    @Synchronized fun releaseAfterIdle() {
        val token = generation
        releaseJob?.cancel(false)
        releaseJob = cleanup.schedule({ enqueue { synchronized(this) {
            if (generation == token) { model?.release(); model = null; modelTiming = null }
        } } }, 60, TimeUnit.SECONDS)
    }
    internal fun releaseForTest(done: () -> Unit) = enqueue { synchronized(this) {
        generation++; releaseJob?.cancel(false); model?.release(); model = null; modelTiming = null
    }; done() }
}

/** 16 kHz mono PCM16. Tests supply synthetic audio to the exact same local decoder. */
interface PcmSource : AutoCloseable {
    fun start()
    fun read(buffer: ByteArray): Int
    fun stop()
}

private class MicrophoneSource : PcmSource {
    @Volatile private var recorder: AudioRecord? = null
    @SuppressLint("MissingPermission") // Entry checks RECORD_AUDIO before capture.
    override fun start() {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
        recorder = audio; check(audio.state == AudioRecord.STATE_INITIALIZED)
        audio.startRecording(); check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
    }
    override fun read(buffer: ByteArray): Int {
        val count = recorder?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: error("Microphone closed")
        check(count > 0) { "Microphone read failed" }; return count
    }
    override fun stop() { runCatching { recorder?.stop() } }
    override fun close() { stop(); runCatching { recorder?.release() }; recorder = null }
}

/** Bounded, volatile PCM only. There is no audio file, HTTP path, telemetry, or recognition service. */
class LocalSpeechInput(
    private val models: SpeechModelRepository,
    private val timing: SpeechTiming = SpeechTiming(),
    private val sourceFactory: () -> PcmSource = { MicrophoneSource() },
) : SpeechInput {
    private class Session(val listener: SpeechInput.Listener, capacity: Int) {
        val cancelled = AtomicBoolean(false)
        val stopCapture = AtomicBoolean(false)
        val captureClosed = CountDownLatch(1)
        val queue = LinkedBlockingQueue<ByteArray>(capacity)
        @Volatile var source: PcmSource? = null
        @Volatile var error: SpeechFailure? = null
        @Volatile var readyAt = 0L
        @Volatile var endedAt = 0L
        @Volatile var capturedBytes = 0
        var energyReported = false
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active: Session? = null
    override fun start(listener: SpeechInput.Listener) {
        cancel()
        val session = Session(listener, timing.maxUtteranceMs / 100 + 2)
        active = session
        Thread({ capture(session) }, "local-mic-capture").start()
        models.enqueue { recognize(session) }
    }
    override fun cancel() {
        val previous = active; active = null
        previous?.cancelled?.set(true); previous?.stopCapture?.set(true)
        previous?.source?.stop(); previous?.queue?.clear()
    }
    private fun post(session: Session, block: () -> Unit) = main.post {
        if (active === session && !session.cancelled.get()) block()
    }
    private fun capture(session: Session) {
        var source: PcmSource? = null
        try {
            if (session.cancelled.get()) return
            source = sourceFactory(); session.source = source
            if (session.cancelled.get()) return
            source.start()
            session.readyAt = SystemClock.elapsedRealtime()
            post(session) { session.listener.onListeningAt(session.readyAt) }
            val buffer = ByteArray(3200) // 100 ms; at most 640 KiB for a 20 s utterance.
            val maximumBytes = 32 * timing.maxUtteranceMs
            while (!session.cancelled.get() && !session.stopCapture.get() && session.capturedBytes < maximumBytes) {
                val count = source.read(buffer)
                if (session.cancelled.get() || session.stopCapture.get()) break
                if (count == -1) break // Finite test source only; AudioRecord failures below are rejected.
                check(count > 0 && count % 2 == 0) { "Invalid PCM input" }
                val remaining = maximumBytes - session.capturedBytes
                val chunk = buffer.copyOf(minOf(count, remaining))
                session.capturedBytes += chunk.size
                if (!session.queue.offer(chunk)) {
                    session.error = SpeechFailure(SpeechError.STT_FAILED, "음성 처리가 지연되어 취소했습니다. 다시 호출해 주세요."); break
                }
                val level = rms(chunk)
                // T3 is an energy onset estimate, not a confidence score or authorization to execute.
                if (!session.energyReported && level > 0.005f) {
                    session.energyReported = true
                    val onset = SystemClock.elapsedRealtime()
                    post(session) { session.listener.onSpeechStartedAt(onset) }
                }
                post(session) { session.listener.onLevel(level) }
            }
        } catch (_: SecurityException) {
            if (!session.cancelled.get()) session.error = SpeechFailure(SpeechError.PERMISSION_REQUIRED, "마이크 권한을 허용해 주세요.")
        } catch (_: Exception) {
            if (!session.cancelled.get() && !session.stopCapture.get()) session.error = SpeechFailure(SpeechError.STT_FAILED, "마이크를 시작하거나 소리를 읽지 못했습니다.")
        } finally {
            runCatching { source?.close() }; session.source = null
            session.endedAt = SystemClock.elapsedRealtime()
            if (session.readyAt > 0) post(session) { session.listener.onSpeechEndedAt(session.endedAt) }
            session.captureClosed.countDown()
        }
    }
    private fun recognize(session: Session) {
        var stream: OnlineStream? = null
        var transcript = ""
        var error: SpeechFailure? = null
        var cold = false; var loadMs = 0L
        try {
            cold = !models.isWarm(timing)
            val start = SystemClock.elapsedRealtime()
            val recognizer = models.get(timing)
            loadMs = SystemClock.elapsedRealtime() - start
            if (session.cancelled.get()) return
            stream = recognizer.createStream()
            var previousPartial = ""
            while (!session.cancelled.get()) {
                if (session.error != null) { error = session.error; break }
                val chunk = session.queue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    if (session.captureClosed.count == 0L) break
                    continue
                }
                val samples = FloatArray(chunk.size / 2) { index ->
                    ((chunk[index * 2].toInt() and 255) or (chunk[index * 2 + 1].toInt() shl 8)).toShort().toFloat() / 32768f
                }
                stream.acceptWaveform(samples, 16000)
                while (!session.cancelled.get() && recognizer.isReady(stream)) recognizer.decode(stream)
                val partial = recognizer.getResult(stream).text.trim()
                if (partial != previousPartial) { previousPartial = partial; post(session) { session.listener.onPartial(partial) } }
                if (recognizer.isEndpoint(stream)) { transcript = partial; break }
            }
            if (session.cancelled.get()) return
            stopAndWait(session)
            if (error == null) error = session.error
            if (error == null && transcript.isBlank()) {
                stream.acceptWaveform(FloatArray(16000), 16000); stream.inputFinished()
                while (!session.cancelled.get() && recognizer.isReady(stream)) recognizer.decode(stream)
                transcript = recognizer.getResult(stream).text.trim()
            }
            if (error == null && transcript.isBlank()) error = SpeechFailure(SpeechError.NO_SPEECH, "잘 듣지 못했어요. 다시 호출해 말해주세요.")
        } catch (_: Exception) {
            error = SpeechFailure(SpeechError.STT_FAILED, "로컬 한국어 음성 인식에 실패했습니다.")
        } finally {
            stopAndWait(session); stream?.release(); session.queue.clear(); models.releaseAfterIdle()
        }
        if (session.cancelled.get()) return
        val ended = session.endedAt
        val metrics = SpeechMetrics(cold, loadMs, if (session.readyAt > 0) (ended - session.readyAt).coerceAtLeast(0) else 0,
            (SystemClock.elapsedRealtime() - ended).coerceAtLeast(0), session.capturedBytes)
        val result = transcript; val failure = error
        post(session) {
            session.listener.onMetrics(metrics)
            if (failure != null) session.listener.onFailure(failure) else session.listener.onRecognition(SpeechRecognitionResult(
                hypotheses = listOf(SpeechHypothesis(result, null, 0)), engine = metrics.engine, onDevice = true,
                audioDurationMs = metrics.captureMs, finalLatencyMs = metrics.finalDecodeMs,
                totalLatencyMs = metrics.modelLoadMs + metrics.captureMs + metrics.finalDecodeMs,
            ))
        }
    }
    private fun stopAndWait(session: Session) {
        session.stopCapture.set(true); session.source?.stop()
        if (!session.captureClosed.await(2, TimeUnit.SECONDS) && !session.cancelled.get()) {
            session.error = SpeechFailure(SpeechError.STT_FAILED, "마이크가 종료되지 않아 명령을 취소했습니다.")
        }
    }
    private fun rms(bytes: ByteArray): Float {
        var sum = 0.0
        for (index in 0 until bytes.size - 1 step 2) {
            val sample = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample
        }
        return (sqrt(sum / maxOf(bytes.size / 2, 1)) / 7000).toFloat().coerceIn(0f, 1f)
    }
}
