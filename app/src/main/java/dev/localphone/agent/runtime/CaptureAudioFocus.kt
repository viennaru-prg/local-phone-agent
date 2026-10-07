package dev.localphone.agent.runtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

interface CaptureFocus {
    fun acquire(onLost: () -> Unit): Boolean
    fun release()
}

/** System ducking only: no transport play/pause is issued to restore the previous state. */
class CaptureAudioFocus(context: Context) : CaptureFocus {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    override fun acquire(onLost: () -> Unit): Boolean {
        release()
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
            }, Handler(Looper.getMainLooper())).build()
        val granted = manager.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) request = next
        return granted
    }
    override fun release() { request?.let(manager::abandonAudioFocusRequest); request = null }
}
