package dev.localphone.agent.runtime

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.speech.*
import kotlin.coroutines.resume
import kotlinx.coroutines.*

data class NativeSpeechSupport(
    val available: Boolean, val status: String, val installedLanguages: List<String> = emptyList(),
    val pendingLanguages: List<String> = emptyList(), val supportedLanguages: List<String> = emptyList(),
    val onlineLanguages: List<String> = emptyList(), val errorCode: Int? = null,
    val configuredService: String? = null, val noiseSuppressorAvailable: Boolean = false,
    val echoCancelerAvailable: Boolean = false, val gainControlAvailable: Boolean = false,
    val unprocessedSourceAvailable: Boolean = false,
) {
    val koreanReady get() = installedLanguages.any { it.startsWith("ko", ignoreCase = true) }
}
suspend fun queryNativeSpeechSupport(context: Context, vocabulary: List<String> = emptyList()): NativeSpeechSupport = withContext(Dispatchers.Main) {
    val base = NativeSpeechSupport(false, "API_UNAVAILABLE",
        noiseSuppressorAvailable = NoiseSuppressor.isAvailable(), echoCancelerAvailable = AcousticEchoCanceler.isAvailable(),
        gainControlAvailable = AutomaticGainControl.isAvailable(), unprocessedSourceAvailable = context.getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
    if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext base
    val available = base.copy(available = true)
    var recognizer: SpeechRecognizer? = null
    try {
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        if (Build.VERSION.SDK_INT < 33) return@withContext available.copy(status = "LANGUAGE_QUERY_REQUIRES_API_33")
        withTimeout(15_000) { suspendCancellableCoroutine { continuation ->
            recognizer!!.checkRecognitionSupport(OnDeviceSpeechInput.recognitionIntent(vocabulary), context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (continuation.isActive) continuation.resume(available.copy(status = "QUERY_COMPLETED",
                            installedLanguages = support.installedOnDeviceLanguages, pendingLanguages = support.pendingOnDeviceLanguages,
                            supportedLanguages = support.supportedOnDeviceLanguages, onlineLanguages = support.onlineLanguages))
                    }
                    override fun onError(error: Int) {
                        if (continuation.isActive) continuation.resume(available.copy(status = "QUERY_ERROR", errorCode = error))
                    }
                })
        } }
    } catch (cancelled: CancellationException) {
        if (cancelled !is TimeoutCancellationException) throw cancelled
        available.copy(status = "QUERY_TIMEOUT")
    } catch (_: Exception) { available.copy(status = "QUERY_EXCEPTION") }
    finally { runCatching { recognizer?.destroy() } }
}
