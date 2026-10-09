package dev.localphone.agent.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import android.util.Log
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/** One-shot Korean recognition. Prefers the on-device recognizer; never sends audio anywhere itself. */
class Listener(private val context: Context, private val onPartial: (String) -> Unit, private val onReady: () -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0

    sealed interface Result {
        data class Text(val text: String, val details: String) : Result
        data class Error(val message: String) : Result
    }

    fun start(done: (Result) -> Unit) {
        stop()
        val id = ++generation
        val started = SystemClock.elapsedRealtime()
        var readyMs: Long? = null; var endedMs: Long? = null; var partials = 0
        val onDevice = android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val r = if (android.os.Build.VERSION.SDK_INT >= 31 && onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var finished = false
        fun finish(result: Result) { if (!finished && id == generation) { finished = true; done(result) } }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (id != generation || finished) return
                readyMs = SystemClock.elapsedRealtime() - started
                Log.i("AgentVoice", "ASR ready ${readyMs}ms onDevice=$onDevice"); onReady()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { endedMs = SystemClock.elapsedRealtime() - started }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                if (id != generation || finished) return
                partials++
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                val hypotheses = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val text = hypotheses.firstOrNull()?.trim().orEmpty()
                val details = JSONObject().put("engine", if (onDevice) "android-on-device" else "android-default")
                    .put("onDevice", onDevice).put("readyMs", readyMs).put("captureAndRecognitionMs", SystemClock.elapsedRealtime() - started)
                    .put("endOfSpeechMs", endedMs).put("partialCount", partials).put("hypotheses", JSONArray().apply {
                        hypotheses.forEachIndexed { rank, value -> put(JSONObject().put("text", value).put("confidence", scores?.getOrNull(rank)).put("rank", rank)) }
                    }).toString()
                Log.i("AgentVoice", "ASR final=$text confidence=${scores?.firstOrNull()} partials=$partials")
                finish(if (text.isEmpty()) Result.Error("말씀을 알아듣지 못했어요.") else Result.Text(text, details))
            }
            override fun onError(error: Int) = finish(Result.Error(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말씀을 알아듣지 못했어요."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요해요."
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "기기에 한국어 음성 인식 모델이 없어요."
                else -> "음성 인식 오류 ($error)"
            }))
        })
        try { r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }) } catch (e: Exception) { Log.w("AgentVoice", "ASR start failed", e); finish(Result.Error("음성 인식을 시작하지 못했어요: ${e.message}")) }
    }

    fun stop() { generation++; recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
}

/** Korean text-to-speech for results and questions. */
class Speaker(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private lateinit var tts: TextToSpeech
    private val mutex = Mutex()
    private val waiting = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (status == TextToSpeech.SUCCESS) {
                    tts.language = Locale.KOREAN
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { id?.let { waiting.remove(it)?.complete(Unit) } }
                        @Deprecated("Deprecated in Java") override fun onError(id: String?) { id?.let { waiting.remove(it)?.complete(Unit) } }
                        override fun onStop(id: String?, interrupted: Boolean) { id?.let { waiting.remove(it)?.complete(Unit) } }
                    })
                }
                ready.complete(status == TextToSpeech.SUCCESS)
            }
        }
    }

    suspend fun say(text: String) = mutex.withLock {
        if (text.isBlank() || withTimeoutOrNull(2000) { ready.await() } != true) return@withLock
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        waiting[id] = done
        try {
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) return@withLock
            if (withTimeoutOrNull(5000) { done.await(); true } != true) {
                Log.w("AgentVoice", "TTS callback timeout; releasing completed task")
                tts.stop()
            }
        } catch (e: CancellationException) { tts.stop(); throw e }
        finally { waiting.remove(id) }
    }

    fun shutdown() { waiting.values.forEach { it.complete(Unit) }; waiting.clear(); tts.shutdown() }
}
