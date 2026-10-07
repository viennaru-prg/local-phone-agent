package dev.localphone.agent.runtime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi

/** Explicit on-device API only. Never creates the default, potentially remote speech service. */
@RequiresApi(31)
class OnDeviceSpeechInput(private val context: Context) : SpeechInput {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    override fun start(listener: SpeechInput.Listener) {
        cancel()
        val session = generation
        try {
            val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = engine
            fun current() = session == generation && recognizer === engine
            fun finish() { if (current()) cancel() }
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (current()) listener.onListening() }
                override fun onRmsChanged(rmsdB: Float) { if (current()) listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
                override fun onPartialResults(results: Bundle?) {
                    if (current()) listener.onPartial(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    finish()
                    if (text.isBlank()) listener.onFailure(SpeechFailure(SpeechError.NO_SPEECH, "잘 듣지 못했어요. 다시 호출해 말해주세요.")) else listener.onFinal(text)
                }
                override fun onError(error: Int) {
                    if (!current()) return
                    finish()
                    val failure = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure(SpeechError.PERMISSION_REQUIRED, "마이크 권한을 허용해주세요.")
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure(SpeechError.NO_SPEECH, "잘 듣지 못했어요. 다시 호출해 말해주세요.")
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechFailure(SpeechError.STT_FAILED, "기기에 한국어 음성 인식이 준비되지 않았어요. 기기 음성 인식 설정을 꺼주세요.")
                        else -> SpeechFailure(SpeechError.STT_FAILED, "기기의 로컬 음성 인식에 실패했습니다.")
                    }
                    listener.onFailure(failure)
                }
                override fun onBeginningOfSpeech() { if (current()) listener.onSpeechStarted() }
                override fun onEndOfSpeech() { if (current()) listener.onSpeechEnded() }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            timeout = Runnable {
                if (current()) { finish(); listener.onError("잘 듣지 못했어요. 다시 눌러 말해주세요.") }
            }.also { main.postDelayed(it, 25_000) }
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (_: Exception) {
            cancel(); listener.onError("음성 인식을 시작하지 못했어요. 설정에서 포함된 음성 인식을 선택해주세요.")
        }
    }
    override fun cancel() {
        generation++
        timeout?.let(main::removeCallbacks); timeout = null
        val engine = recognizer; recognizer = null
        engine?.cancel(); engine?.destroy()
    }
}
