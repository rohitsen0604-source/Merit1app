package com.scos3.camera.capture

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Light, short haptic feedback used as camera shutter feedback after a
 * successful capture. Safe on every supported API level and on devices
 * without a vibrator: every call is wrapped so it can never crash.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = resolveVibrator(context.applicationContext)

    /** A short, subtle shutter tick (only fires when a vibrator exists). */
    fun shutterTick() {
        val v = vibrator ?: return
        runCatching {
            if (v.hasVibrator()) {
                v.vibrate(
                    VibrationEffect.createOneShot(SHUTTER_TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    companion object {
        private const val SHUTTER_TICK_MS = 65L
    }
}
