package io.databang.digidash.core.alert

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Plays a short vibration + beep for threshold alerts. Safe to call from any
 * thread; no-ops when the device has no vibrator. Used by [AlertMonitor].
 */
class Alerter(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun alert(critical: Boolean) {
        vibrate(critical)
        beep(critical)
    }

    private fun vibrate(critical: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val pattern = if (critical) longArrayOf(0, 250, 120, 250) else longArrayOf(0, 180)
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun beep(critical: Boolean) {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            val type = if (critical) ToneGenerator.TONE_CDMA_HIGH_L
            else ToneGenerator.TONE_PROP_BEEP
            tone.startTone(type, if (critical) 500 else 200)
            // Release after the tone finishes.
            Thread {
                Thread.sleep(700)
                runCatching { tone.release() }
            }.start()
        }
    }
}
