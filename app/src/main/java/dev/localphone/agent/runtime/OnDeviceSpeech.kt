package dev.localphone.agent.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import dev.localphone.core.*

/** Native local service only. No default recognizer or network fallback is ever created. */
@RequiresApi(31)
class OnDeviceSpeechInput(
    private val context: Context, private val vocabulary: List<String> = emptyList(),
    private val maximumMs: Long = 25_000,
    private val replaySource: (() -> ParcelFileDescriptor)? = null,
) : SpeechInput {
    companion object {
        fun hypotheses(bundle: Bundle?): List<SpeechHypothesis> {
            val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val scores = bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            return texts.mapIndexedNotNull { rank, text ->
                if (text.isBlank() || text.length > 1000) null else SpeechHypothesis(text,
                    scores?.getOrNull(rank)?.takeIf { it.isFinite() && it in 0f..1f }, rank)
            }
        }
        fun recognitionIntent(vocabulary: List<String>): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, false)
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(vocabulary.take(48)))
            }
            // Keep the native endpointer defaults. Silence overrides may be ignored or truncate speech.
        }
    }
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private var audio: ParcelFileDescriptor? = null
    override fun start(listener: SpeechInput.Listener) {
        cancel()
        val session = generation
        val started = SystemClock.elapsedRealtime()
        var readyAt: Long? = null; var endedAt: Long? = null
        val partials = mutableListOf<SpeechPartial>()
        try {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                listener.onFailure(SpeechFailure(SpeechError.STT_FAILED, "기기의 온디바이스 음성 인식이 지원되지 않습니다. 음성 평가 화면에서 지원 상태를 확인해 주세요.")); return
            }
            val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = engine
            fun current() = session == generation && recognizer === engine
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!current()) return
                    readyAt = SystemClock.elapsedRealtime(); listener.onListeningAt(readyAt!!)
                }
                override fun onRmsChanged(rmsdB: Float) { if (current()) listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
                override fun onPartialResults(results: Bundle?) {
                    if (!current()) return
                    val candidates = hypotheses(results)
                    if (partials.size == 32) partials.removeAt(0)
                    partials += SpeechPartial(candidates, SystemClock.elapsedRealtime() - started)
                    listener.onPartial(candidates.firstOrNull()?.text.orEmpty())
                }
                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    val finishedAt = SystemClock.elapsedRealtime()
                    val candidates = hypotheses(results)
                    val result = SpeechRecognitionResult(candidates, partials.toList(), engine = "android-native-on-device",
                        onDevice = true, readyLatencyMs = readyAt?.minus(started),
                        audioDurationMs = readyAt?.let { ((endedAt ?: finishedAt) - it).coerceAtLeast(0) },
                        finalLatencyMs = endedAt?.let { finishedAt - it }, totalLatencyMs = finishedAt - started,
                        biasCount = vocabulary.take(48).size, source = if (replaySource == null) "MICROPHONE" else "REPLAY_REQUESTED")
                    cancel()
                    if (candidates.isEmpty()) listener.onFailure(SpeechFailure(SpeechError.NO_SPEECH, "잘 듣지 못했어요. 다시 호출해 말해주세요."))
                    else {
                        listener.onMetrics(SpeechMetrics(false, 0, result.audioDurationMs ?: 0, result.finalLatencyMs ?: 0, 0, result.engine))
                        listener.onRecognition(result)
                    }
                }
                override fun onError(error: Int) {
                    if (!current()) return
                    cancel()
                    listener.onFailure(when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure(SpeechError.PERMISSION_REQUIRED, "마이크 권한을 허용해주세요.")
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure(SpeechError.NO_SPEECH, "잘 듣지 못했어요. 다시 호출해 말해주세요.")
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechFailure(SpeechError.STT_FAILED, "한국어 온디바이스 모델이 준비되지 않았습니다. 음성 평가 화면에서 한국어 지원 상태를 확인해 주세요.")
                        else -> SpeechFailure(SpeechError.STT_FAILED, "기기의 로컬 음성 인식에 실패했습니다. 오류 $error (클라우드로 전환하지 않았습니다).")
                    })
                }
                override fun onBeginningOfSpeech() { if (current()) listener.onSpeechStartedAt(SystemClock.elapsedRealtime()) }
                override fun onEndOfSpeech() {
                    if (current()) { endedAt = SystemClock.elapsedRealtime(); listener.onSpeechEndedAt(endedAt!!) }
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val request = recognitionIntent(vocabulary)
            if (replaySource != null) {
                check(Build.VERSION.SDK_INT >= 33)
                audio = replaySource.invoke()
                request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            }
            timeout = Runnable {
                if (current()) { cancel(); listener.onFailure(SpeechFailure(SpeechError.STT_FAILED, "음성 인식이 오래 걸려 취소했습니다. 다시 말해 주세요.")) }
            }.also { main.postDelayed(it, maximumMs.coerceIn(3000, 30_000)) }
            engine.startListening(request)
        } catch (_: Exception) {
            cancel(); listener.onFailure(SpeechFailure(SpeechError.STT_FAILED, "온디바이스 음성 인식을 시작하지 못했습니다. 음성 평가 화면에서 지원 상태를 확인해 주세요."))
        }
    }
    override fun cancel() {
        generation++
        timeout?.let(main::removeCallbacks); timeout = null
        val engine = recognizer; recognizer = null
        runCatching { engine?.cancel() }; runCatching { engine?.destroy() }
        runCatching { audio?.close() }; audio = null
    }
}
