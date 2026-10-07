package dev.localphone.agent.runtime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.localphone.agent.data.SecureSettings

interface VoiceFeedback {
    fun ready()
    fun success()
    fun failure()
}
class HapticVoiceFeedback(context: Context, private val settings: SecureSettings) : VoiceFeedback {
    private val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    private fun pattern(times: LongArray) {
        if (settings.get("voice_haptics") != "no") runCatching { vibrator.vibrate(VibrationEffect.createWaveform(times, -1)) }
    }
    override fun ready() = pattern(longArrayOf(0, 25))
    override fun success() = pattern(longArrayOf(0, 15, 45, 15))
    override fun failure() = pattern(longArrayOf(0, 50, 60, 50))
}
