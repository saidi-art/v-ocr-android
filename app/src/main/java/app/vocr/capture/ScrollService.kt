package app.vocr.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Gesture-only accessibility service: swipes the foreground app up (scrolls down) at a set pace. Reads nothing. */
class ScrollService : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    /** One swipe from y0 to y1 (fractions of the screen height) at the horizontal centre, lasting durMs. */
    fun swipe(w: Int, h: Int, y0: Float, y1: Float, durMs: Long, done: (Boolean) -> Unit) {
        val p = Path().apply { moveTo(w * 0.5f, h * y0); lineTo(w * 0.5f, h * y1) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durMs)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done(true) }
            override fun onCancelled(d: GestureDescription?) { done(false) }
        }, null)
        if (!ok) done(false)
    }

    companion object { @Volatile var instance: ScrollService? = null }
}
