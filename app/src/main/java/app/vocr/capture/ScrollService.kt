package app.vocr.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.graphics.Bitmap

/** Gesture-only accessibility service: swipes the foreground app up (scrolls down) at a set pace. Reads no text or window content. For the bottom-of-page check it takes a tiny in-memory grey screenshot after each swipe (see thumb). */
class ScrollService : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    /** One swipe from y0 to y1 (fractions of the screen height) at the horizontal centre, lasting durMs. */
    fun swipe(w: Int, h: Int, y0: Float, y1: Float, durMs: Long, xFrac: Float = X0, done: (Boolean) -> Unit) {
        val p = Path().apply { moveTo(w * xFrac, h * y0); lineTo(w * xFrac, h * y1) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durMs)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done(true) }
            override fun onCancelled(d: GestureDescription?) { done(false) }
        }, null)
        if (!ok) done(false)
    }

    /**
     * Small grey thumbnail of the screen (48x96) for the bottom-of-page check, or null when a screenshot is not available.
     * Pixels only: no text or window content is read; the picture is reduced to 48x96 grey values in memory and never stored or sent. The right edge (floating widget) and top strip (clock) are blanked.
     */
    fun thumb(done: (IntArray?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 30) { done(null); return }
        val ok = runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val out = runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        r.hardwareBuffer.close()
                        val sm = sw?.let { Bitmap.createScaledBitmap(it, 48, 96, true) }
                        val a = IntArray(48 * 96)
                        if (sm != null) for (y in 0 until 96) for (x in 0 until 48) {
                            val c = sm.getPixel(x, y)
                            a[y * 48 + x] = if (x >= 39 || y < 6) 0 else (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
                        }
                        if (sm == null) null else a
                    }.getOrNull()
                    done(out)
                }
                override fun onFailure(code: Int) { done(null) }
            })
        }.isSuccess
        if (!ok) done(null)
    }

    companion object { @Volatile var instance: ScrollService? = null
        /** Swipe column: 35% from the left, clear of centred overlays such as Gemini's jump-to-bottom arrow (at 43-57% width, 79-86% height). */
        const val X0 = 0.35f }
}
