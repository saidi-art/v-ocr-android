package app.vocr.capture

/**
 * How far the page really moved during one swipe, from accessibility scroll events alone (their delta/position
 * fields, never any content). Pure, unit-tested. Two signal shapes exist (on-device diag, Oct 2026):
 * delta apps (Grok's ScrollView, Gemini's RecyclerView) report the pixel delta per event - the sign varies by
 * app, so only absolute distances are used; position apps (Claude, DeepSeek Compose views) report no delta but
 * a live absolute position. A position app can re-anchor mid-gesture (Compose list recycling): a single step
 * larger than one screen is not scrolling, so the reading is marked unreliable and pixels take over.
 * Events arrive during the gesture (~100 ms cadence), so the reading is complete shortly after the swipe ends.
 */
class ScrollSignal {
    enum class Kind { NONE, DELTA, POSITION }
    class Reading(val px: Int, val kind: Kind, val events: Int, val reliable: Boolean)

    private var screenH = 2340
    private var pkg: String? = null
    private var events = 0
    private var sumDelta = 0L
    private var deltaN = 0
    private var posN = 0
    private var lastY = Int.MIN_VALUE
    private var sumSteps = 0L
    private var jump = false

    /** Start measuring one swipe. heightPx is the screen height: a single position step larger than that is a re-anchor, not scrolling. */
    @Synchronized fun arm(heightPx: Int) {
        screenH = maxOf(heightPx, 800); pkg = null; events = 0; sumDelta = 0; deltaN = 0; posN = 0; lastY = Int.MIN_VALUE; sumSteps = 0; jump = false
    }

    /** One TYPE_VIEW_SCROLLED event, reduced to its numbers by the service. dY = -1 when the app reports no delta; y is its scroll position (0 when not reported). */
    @Synchronized fun onScroll(pkgName: String?, dY: Int, y: Int) {
        val p = pkgName ?: return
        val cur = pkg
        if (cur == null) pkg = p else if (cur != p) return   // one foreground app is being captured; launcher/recents noise is ignored
        events++
        if (dY != -1) { sumDelta += kotlin.math.abs(dY); deltaN++ }
        if (y > 0 || posN > 0) {
            val lp = lastY
            if (lp != Int.MIN_VALUE) {
                val step = kotlin.math.abs(y - lp)
                if (step > screenH) jump = true else sumSteps += step
            }
            lastY = y; posN++
        }
    }

    /** The distance since arm(). px = -1: nothing usable (no events, or a re-anchor jump). px ~0 with events > 0: the page did not move. */
    @Synchronized fun read(): Reading {
        if (events == 0) return Reading(-1, Kind.NONE, 0, false)
        if (jump) return Reading(-1, if (deltaN > 0) Kind.DELTA else Kind.POSITION, events, false)
        if (deltaN > 0) return Reading(sumDelta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), Kind.DELTA, events, true)
        if (posN >= 2) return Reading(sumSteps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), Kind.POSITION, events, true)
        return Reading(-1, Kind.NONE, events, false)
    }
}
