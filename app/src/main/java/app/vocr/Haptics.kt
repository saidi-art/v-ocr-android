package app.vocr

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/** Haptic vocabulary from the ScrollRSI sheet, with API fallbacks. View-based for taps in the app, Vibrator-based for the service and queue. */
object Haptics {
    fun secondary(v: View) { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    fun primary(v: View) { v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 23) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.KEYBOARD_TAP) }
    fun toggle(v: View, on: Boolean) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) (if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF) else HapticFeedbackConstants.CLOCK_TICK)
    }
    fun detent(v: View) { v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK) }

    private fun vib(c: Context): Vibrator? = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private fun play(c: Context, e: VibrationEffect) { runCatching { vib(c)?.vibrate(e) } }
    private fun predefined(c: Context, id: Int) = play(c, VibrationEffect.createPredefined(id))
    private fun pulse(c: Context, ms: Long, amp: Int) {
        val v = vib(c) ?: return
        if (v.hasAmplitudeControl()) play(c, VibrationEffect.createOneShot(ms, amp)) else predefined(c, VibrationEffect.EFFECT_TICK)
    }

    /** Recording starts. */
    fun recStart(c: Context) = predefined(c, VibrationEffect.EFFECT_HEAVY_CLICK)
    /** Job saved (CONFIRM on API 30+, otherwise a light tap). */
    fun saved(c: Context) = if (Build.VERSION.SDK_INT >= 30) play(c, VibrationEffect.createWaveform(longArrayOf(0, 18, 60, 30), intArrayOf(0, 120, 0, 200), -1)) else predefined(c, VibrationEffect.EFFECT_CLICK)
    /** Job failed. */
    fun failure(c: Context) = if (Build.VERSION.SDK_INT >= 30) play(c, VibrationEffect.createWaveform(longArrayOf(0, 40, 50, 40, 50, 60), intArrayOf(0, 200, 0, 200, 0, 255), -1)) else predefined(c, VibrationEffect.EFFECT_HEAVY_CLICK)

    // Auto-scroll (user override of the sheet's "none"): switchable in Settings, default on.
    fun autoOn(c: Context) = c.getSharedPreferences("vocr", Context.MODE_PRIVATE).getBoolean("haptics", true)
    /** Soft tick when auto-scroll begins. */
    fun autoStart(c: Context) { if (autoOn(c)) predefined(c, VibrationEffect.EFFECT_TICK) }
    /** Very light pulse while it runs: one per swipe at full pace, roughly every 1.5 s while crawling. */
    fun autoPulse(c: Context) { if (autoOn(c)) pulse(c, 10, 28) }
    /** Gentle click when auto-scroll ends. */
    fun autoEnd(c: Context) { if (autoOn(c)) predefined(c, VibrationEffect.EFFECT_CLICK) }
}
