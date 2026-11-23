package dev.agentic.core.designsystem

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * High-fidelity native tactile and haptic feedback manager for Antigravity Android.
 * Provides subtle mechanical ticks, click confirmations, and gesture snap responses
 * matching modern Pixel / Android 14+ UI standards.
 */
object HapticEngine {

    enum class HapticType {
        LIGHT_TICK,     // Quick subtle tick for slider, drawer edge, minor toggle
        STANDARD_CLICK, // Standard button tap, navigation selection
        CONFIRMATION,   // Success action, task finished, code copied
        REJECT_ERROR,   // Action blocked, invalid gesture, error dialog
        HEAVY_SNAP      // Drawer snap shut or snap open threshold
    }

    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        if (vibrator == null) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mgr?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }

    /**
     * Perform haptic feedback using Android's native system Vibrator / VibrationEffect.
     */
    fun perform(type: HapticType = HapticType.STANDARD_CLICK, view: View? = null) {
        val v = vibrator
        if (v != null && v.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val effect = when (type) {
                        HapticType.LIGHT_TICK -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                            } else {
                                VibrationEffect.createOneShot(8, 60)
                            }
                        }
                        HapticType.STANDARD_CLICK -> {
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                        }
                        HapticType.CONFIRMATION -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                            } else {
                                VibrationEffect.createOneShot(20, 180)
                            }
                        }
                        HapticType.REJECT_ERROR -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                            } else {
                                VibrationEffect.createWaveform(longArrayOf(0, 15, 40, 15), intArrayOf(0, 180, 0, 180), -1)
                            }
                        }
                        HapticType.HEAVY_SNAP -> {
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                        }
                    }
                    v.vibrate(effect)
                    return
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val duration = when (type) {
                        HapticType.LIGHT_TICK -> 8L
                        HapticType.STANDARD_CLICK -> 15L
                        HapticType.CONFIRMATION -> 25L
                        HapticType.REJECT_ERROR -> 35L
                        HapticType.HEAVY_SNAP -> 30L
                    }
                    val amplitude = when (type) {
                        HapticType.LIGHT_TICK -> 60
                        HapticType.STANDARD_CLICK -> 140
                        HapticType.CONFIRMATION -> 200
                        HapticType.REJECT_ERROR -> 255
                        HapticType.HEAVY_SNAP -> 220
                    }
                    v.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                    return
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(15L)
                    return
                }
            } catch (_: Exception) {}
        }

        // Fallback: View-level haptic feedback
        if (view != null) {
            val constant = when (type) {
                HapticType.LIGHT_TICK -> HapticFeedbackConstants.CLOCK_TICK
                HapticType.STANDARD_CLICK -> HapticFeedbackConstants.KEYBOARD_TAP
                HapticType.CONFIRMATION, HapticType.HEAVY_SNAP -> HapticFeedbackConstants.LONG_PRESS
                HapticType.REJECT_ERROR -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
                    else HapticFeedbackConstants.LONG_PRESS
                }
            }
            view.performHapticFeedback(constant)
        }
    }
}
