package app.vocr.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.graphics.Bitmap

/** Gesture accessibility service: swipes the foreground app up (scrolls down) at a set pace, and watches scroll events so a capture knows how far the page really moved (their delta/position numbers, never text or window content). For the bottom-of-page check it takes a tiny in-memory grey screenshot after each swipe (see thumb). */
class ScrollService : AccessibilityService() {
    /** How far the page really moved during the current swipe (scroll-event numbers only, never content). Armed per swipe by the capture loop. */
    val signal = ScrollSignal()
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        signal.onScroll(e.packageName?.toString(), e.scrollDeltaY, e.scrollY)
    }
    /**
     * Experimental, opt-in (the direct-text menu chip): the visible text of the foreground app, read straight from its views
     * instead of OCR. Called only while a capture runs with the setting on; the text never leaves the phone.
     */
    fun screenText(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<String>()
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo) {
            if (n.isVisibleToUser) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) out.add(t)
            }
            for (i in 0 until n.childCount) runCatching { n.getChild(i)?.let { walk(it) } }
        }
        runCatching { walk(root) }
        return out
    }

    override fun onInterrupt() {}
    override fun onServiceConnected() { super.onServiceConnected(); instance = this; app.vocr.homeTick.intValue++ }
    override fun onDestroy() { if (instance === this) instance = null; app.vocr.homeTick.intValue++; super.onDestroy() }

    /** One swipe from y0 to y1 (fractions of the screen height) at the horizontal centre, lasting durMs. */
    fun swipe(w: Int, h: Int, y0: Float, y1: Float, durMs: Long, xFrac: Float = X0, tailMs: Long = 0L, done: (Boolean) -> Unit) {
        // Never hand the gesture builder a point off the screen or a zero/overlong duration: it throws and would take the whole service down.
        // tailMs > 0: the finger stays still at the end for that long before lifting, so the page does not fling on after the drag (long screenshots need a predictable distance).
        var s1: GestureDescription.StrokeDescription? = null; var tail: Path? = null
        val g = try {
            val x = (w * xFrac).coerceIn(1f, w - 1f); val ya = (h * y0).coerceIn(1f, h - 1f); val yb = (h * y1).coerceIn(1f, h - 1f)
            val d = durMs.coerceIn(1L, GestureDescription.getMaxGestureDuration() / 2)
            val p = Path().apply { moveTo(x, ya); lineTo(x, yb) }
            // with a tail the drag is the first part of a continued stroke: Android wants the continuation in a second gesture, dispatched when the first one completes
            if (tailMs > 0) { tail = Path().apply { moveTo(x, yb) }; s1 = GestureDescription.StrokeDescription(p, 0, d, true); GestureDescription.Builder().addStroke(s1).build() }
            else GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, d)).build()
        } catch (e: Exception) { app.vocr.VocrApp.note("swipe build failed", e); done(false); return }
        val ok = try {
            dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    val st = s1; val tp = tail
                    if (st == null || tp == null) { done(true); return }
                    // the page has been dragged; hold the finger still, then lift. If the hold cannot be sent the drag already happened, so the swipe still counts.
                    val sent = try { dispatchGesture(GestureDescription.Builder().addStroke(st.continueStroke(tp, 0, tailMs, false)).build(), object : GestureResultCallback() {
                        override fun onCompleted(d: GestureDescription?) { done(true) }
                        override fun onCancelled(d: GestureDescription?) { done(true) }
                    }, null) } catch (e: Exception) { app.vocr.VocrApp.note("tail dispatch failed", e); false }
                    if (!sent) done(true)
                }
                override fun onCancelled(d: GestureDescription?) { done(false) }
            }, null)
        } catch (e: Exception) { app.vocr.VocrApp.note("dispatchGesture failed", e); false }
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


    /** Full-resolution screenshot (software ARGB bitmap) for the screenshot feature, or null when not available. Retries once or twice when Android rate-limits. */
    fun fullShot(done: (Bitmap?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 30) { done(null); return }
        fun attempt(left: Int) {
            val ok = runCatching {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        val out = runCatching {
                            val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                            val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            r.hardwareBuffer.close(); hw?.recycle(); sw
                        }.getOrNull()
                        done(out)
                    }
                    override fun onFailure(code: Int) {
                        if (left > 0) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ attempt(left - 1) }, 450) else done(null)
                    }
                })
            }.isSuccess
            if (!ok) done(null)
        }
        attempt(4)
    }

    companion object {
        /** True when Android's enabled-services list contains this service, even if the system has not (re)connected it yet. */
        fun enabledInSettings(ctx: android.content.Context): Boolean {
            val me = android.content.ComponentName(ctx, ScrollService::class.java)
            val list = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { android.content.ComponentName.unflattenFromString(it) == me }
        }
        @Volatile var instance: ScrollService? = null
        /** Swipe column: 35% from the left, clear of centred overlays such as Gemini's jump-to-bottom arrow (at 43-57% width, 79-86% height). */
        const val X0 = 0.35f }
}
