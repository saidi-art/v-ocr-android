package app.vocr.core

import kotlin.math.abs

/**
 * Fixed top/bottom bands of a recorded screen (a chat header, a composer), proven by the phone's own scroll
 * distance: a row is furniture when, in most moving pairs, its pixels repeat at the SAME position while the page
 * beneath moved by dy. A translucent bar lets the page show through, so a row only has to hold in half the pairs.
 * Rows and pixel equality only, never text. Detection scale (360 px wide). Pure, unit-tested.
 *
 * The bands are cropped at read time, and never from the first or last strip (the owner's no-omission rule).
 */
class ScrollBands(private val w: Int, private val h: Int) {
    private val hits = IntArray(h)
    private val dySeen = HashSet<Int>()
    private var maxDy = 0
    var pairs = 0; private set

    /** One pair of frames whose page movement dy (det rows) is proven (a trace). */
    fun update(a: Gray, b: Gray, dy: Int) {
        if (a.w != w || a.h != h || b.w != w || b.h != h || dy < MIN_DY || dy >= h / 2) return
        pairs++; dySeen.add(dy); if (dy > maxDy) maxDy = dy
        // the bottom dy rows have no shifted source on screen, so movement there cannot be ruled out:
        // they are never judged here; band(false) may extend into them only below rows that WERE proven
        for (y in 0 until h - dy) {
            var same = 0; var explained = 0; var n = 0
            var x = 1
            while (x < w) {
                val cb = b.d[y * w + x]
                if (abs(cb - a.d[y * w + x]) <= 10f) same++
                if (y + dy < h && abs(cb - a.d[(y + dy) * w + x]) <= 10f) explained++
                n++; x += 3
            }
            if (n >= 8 && same * 100 >= 70 * n && explained * 100 < 60 * n) hits[y]++
        }
    }

    private fun band(fromTop: Boolean): Int {
        // one repeated step size could be periodic page content faking furniture: no proof, no crop
        if (pairs < MIN_PAIRS || dySeen.size < 2) return 0
        val cap = h / 4
        var last = -1; var gap = 0; var n = 0
        // bottom: the lowest maxDy rows are unverifiable (see update); tolerate them, then require proof
        var lead = if (fromTop) 0 else maxDy
        val rng = if (fromTop) (0 until cap) else ((h - 1) downTo (h - cap))
        for (y in rng) {
            if (hits[y] * 2 >= pairs) { last = y; gap = 0; n++ }
            else if (lead > 0) { lead-- }
            else { gap++; if (gap > GAP) break }
        }
        if (n < MIN_ROWS || last < 0) return 0
        return if (fromTop) last + 1 else h - last
    }

    /** Rows of fixed furniture at the top (0 = none proven). */
    fun topBand(): Int = band(true)
    /** Rows of fixed furniture at the bottom (0 = none proven). */
    fun bottomBand(): Int = band(false)

    companion object { const val MIN_DY = 8; const val MIN_PAIRS = 4; const val MIN_ROWS = 4; const val GAP = 24 }
}
