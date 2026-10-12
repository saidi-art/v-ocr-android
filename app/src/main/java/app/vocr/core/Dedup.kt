package app.vocr.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** Grayscale image, values 0..255. */
class Gray(val w: Int, val h: Int, val d: FloatArray)

sealed class Decision {
    object Skip : Decision()
    object Full : Decision()
    /** crop rows [top, bottom) in detection-scale coordinates */
    data class Crop(val top: Int, val bottom: Int) : Decision()
}

object Hash {
    private val cosT = Array(8) { u -> FloatArray(32) { x -> cos((2 * x + 1) * u * PI / 64).toFloat() } }

    /** 64-bit pHash of a 32x32 gray image (low 8x8 DCT block vs its median). */
    fun phash(g: Gray): Long {
        require(g.w == 32 && g.h == 32)
        val tmp = Array(8) { FloatArray(32) }
        for (u in 0 until 8) for (y in 0 until 32) {
            var s = 0f
            for (x in 0 until 32) s += g.d[y * 32 + x] * cosT[u][x]
            tmp[u][y] = s
        }
        val dct = FloatArray(64)
        for (v in 0 until 8) for (u in 0 until 8) {
            var s = 0f
            for (y in 0 until 32) s += tmp[u][y] * cosT[v][y]
            dct[v * 8 + u] = s
        }
        val sorted = dct.sortedArray()
        val med = (sorted[31] + sorted[32]) / 2f
        var bits = 0L
        for (i in 0 until 64) if (dct[i] > med) bits = bits or (1L shl i)
        return bits
    }

    fun similarity(a: Long, b: Long): Double = 1.0 - java.lang.Long.bitCount(a xor b) / 64.0
}

/**
 * Streaming port of the web v1 dedup (pHash filter + vertical scroll detection).
 * Feed frames in order; get a decision per frame. Only the previous gray frame is kept.
 */
/** Adaptive sampling rate: speeds up when scrolling outruns the sampling (no match, or a jump near the match limit), eases back when calm. */
class RateCtl(private val base: Double, private val maxFps: Double = 60.0) {
    var cur = base; private set
    fun update(sb: StripBuilder): Double {
        cur = when {
            sb.lastFailed -> minOf(maxFps, maxOf(12.0, cur * 1.7))
            sb.lastSpan > 0 && sb.lastDy > 0.30 * sb.lastSpan -> minOf(maxFps, cur * 1.25)   // matched, but close to the limit
            else -> maxOf(base, cur * 0.93)
        }
        return cur
    }
}

class StripBuilder(
    private val simThreshold: Double = 0.95,
    private val minStripFrac: Double = 0.30,
    private val overlapPx: Int = 18,
) {
    var total = 0; private set
    var dup = 0; private set
    var full = 0; private set
    var cropped = 0; private set
    /** true when the last processed frame changed but no scroll could be matched (fast scroll or new screen) */
    var lastFailed = false; private set
    /** last matched scroll distance (rows of the analysis frame) and the scrollable span it was matched in */
    var lastDy = 0; private set
    var lastSpan = 0; private set

    private val hdrHist = IntArray(64); private val ftrHist = IntArray(64)   // buckets of 4 analysis rows
    /** Fixed header/footer rows (analysis frame) once seen consistently, else -1. Used to trim whole frames to the scrolling band. */
    private fun mode(h: IntArray): Int { var bi = -1; var bv = 2; for (i in 2 until h.size) if (h[i] > bv) { bv = h[i]; bi = i }; return if (bi < 0) -1 else bi * 4 }
    val bandTop: Int get() = mode(hdrHist)
    val bandFooter: Int get() = mode(ftrHist)
    private var prevHash: Long? = null
    private var prev: Gray? = null
    private var acc = 0
    private var vb = 0

    fun push(hash: Long, g: Gray): Decision {
        total++
        lastFailed = false
        val p = prev
        val ph = prevHash
        if (p != null && ph != null && Hash.similarity(hash, ph) > simThreshold && meanAbsDiff(g, p) < 2.0f) {
            dup++; return Decision.Skip   // keep prev as reference; nothing changed
        }
        var out: Decision = Decision.Skip
        if (p == null) {
            full++; out = Decision.Full
        } else {
            val hv = headerRows(p, g); val fv = footerRows(p, g)
            if (hv in 8..255) hdrHist[(hv / 4).coerceAtMost(63)]++
            if (fv in 8..255) ftrHist[(fv / 4).coerceAtMost(63)]++
            val dy = detectScroll(p, g)
            if (dy == 0) {
                full++; acc = 0; out = Decision.Full; lastFailed = true
            } else {
                acc += dy; lastDy = dy; lastSpan = g.h - headerRows(p, g) - footerRows(p, g)
                vb = g.h - footerRows(p, g)
                if (acc >= minStripFrac * g.h) {
                    out = Decision.Crop(maxOf(0, vb - acc - overlapPx), vb)
                    cropped++; acc = 0
                } else dup++
            }
        }
        prevHash = hash; prev = g
        return out
    }

    /** Call at the end: returns a final crop for unseen area still accumulated. */
    fun flush(): Decision {
        val p = prev ?: return Decision.Skip
        if (acc <= 0) return Decision.Skip
        val b = if (vb > 0) vb else p.h
        cropped++
        return Decision.Crop(maxOf(0, b - acc - overlapPx), b)
    }

    companion object {
        fun meanAbsDiff(a: Gray, b: Gray): Float {
            var s = 0.0; for (i in a.d.indices) s += abs(a.d[i] - b.d[i]); return (s / a.d.size).toFloat()
        }

        private const val BANDS = 3

        /** Row-edge energy per vertical band (a floating overlay corrupts at most one band). */
        private fun rowEnergy(g: Gray): Array<FloatArray> = Array(BANDS) { b ->
            val x0 = b * g.w / BANDS; val x1 = (b + 1) * g.w / BANDS
            FloatArray(g.h) { y -> var s = 0f; val o = y * g.w; for (x in x0 + 1 until x1) s += abs(g.d[o + x] - g.d[o + x - 1]); s }
        }

        /** Row unchanged between frames: tiny mean change, or nearly all pixels equal (tolerates a floating overlay or a blinking icon). */
        private fun rowsEqual(a: Gray, b: Gray, y: Int): Boolean {
            var s = 0.0; var same = 0; val o = y * a.w
            for (x in 0 until a.w) { val d = abs(a.d[o + x] - b.d[o + x]); s += d; if (d <= 4f) same++ }
            return s / a.w < 1.0 || same >= 0.86 * a.w
        }

        /** Rows at the bottom identical between frames = fixed footer. */
        fun footerRows(prev: Gray, cur: Gray): Int {
            var n = 0
            while (n < (cur.h * 0.25).toInt() && rowsEqual(prev, cur, cur.h - 1 - n)) n++
            return n
        }

        /** Rows at the top identical between frames = fixed header (status bar, browser chrome). */
        fun headerRows(prev: Gray, cur: Gray): Int {
            var n = 0
            while (n < (cur.h * 0.25).toInt() && rowsEqual(prev, cur, n)) n++
            return n
        }

        /**
         * dy>0 when content moved UP by dy rows (scrolling down), 0 if no trustworthy match.
         * Matches row-energy profiles (fast, and robust on pages with big blank areas), ignoring the
         * fixed header/footer, then confirms with a pixel check on the overlap.
         */
        fun detectScroll(prev: Gray, cur: Gray): Int {
            val h = cur.h; val w = cur.w
            val top = headerRows(prev, cur); val bot = h - footerRows(prev, cur)
            val span = bot - top
            if (span < 40) return 0
            val ep = rowEnergy(prev); val ec = rowEnergy(cur)
            val thr = (w / BANDS) * 1.5f
            val maxdy = (span * 0.9).toInt()
            val e0 = ec[0]; val e1 = ec[1]; val e2 = ec[2]; val p0 = ep[0]; val p1 = ep[1]; val p2 = ep[2]
            fun score(dy: Int): Float {
                var d0 = 0f; var t0 = 0f; var n0 = 0
                var d1 = 0f; var t1 = 0f; var n1 = 0
                var d2 = 0f; var t2 = 0f; var n2 = 0
                for (y in top until bot - dy) {
                    var a = e0[y]; var c = p0[y + dy]; var sm = a + c
                    if (sm > thr) { d0 += abs(a - c); t0 += sm; n0++ }
                    a = e1[y]; c = p1[y + dy]; sm = a + c
                    if (sm > thr) { d1 += abs(a - c); t1 += sm; n1++ }
                    a = e2[y]; c = p2[y + dy]; sm = a + c
                    if (sm > thr) { d2 += abs(a - c); t2 += sm; n2++ }
                }
                val s0 = if (n0 >= 8) d0 / t0 else 2f
                val s1 = if (n1 >= 8) d1 / t1 else 2f
                val s2 = if (n2 >= 8) d2 / t2 else 2f
                var valid = 0; if (n0 >= 8) valid++; if (n1 >= 8) valid++; if (n2 >= 8) valid++
                if (valid < 2) return 2f
                return maxOf(minOf(s0, s1), minOf(maxOf(s0, s1), s2))   // median of three
            }
            // coarse pass on every 2nd dy, then refine around the best few
            val cs = FloatArray(maxdy + 2) { 2f }
            var dyc = 2
            while (dyc <= maxdy) { cs[dyc] = score(dyc); dyc += 2 }
            var best = 2f; var bestDy = 0
            repeat(4) {
                var bi = -1; var bv = 2f
                var k = 2
                while (k <= maxdy) { if (cs[k] < bv) { bv = cs[k]; bi = k }; k += 2 }
                if (bi < 0) return@repeat
                for (d in bi - 1..bi + 1) {
                    if (d < 2 || d > maxdy) continue
                    val v = if (d == bi) bv else score(d)
                    if (v < best) { best = v; bestDy = d }
                }
                for (d in bi - 4..bi + 4 step 2) if (d in 2..maxdy) cs[d] = 2f   // suppress this neighbourhood
            }
            if (bestDy == 0 || best > 0.30f) return 0
            // pixel confirmation on the overlap: most pixels must agree (an overlay may spoil a minority)
            var agree = 0; var n = 0
            var y = top
            while (y < bot - bestDy) {
                val ro = y * w; val po = (y + bestDy) * w
                var x = 0
                while (x < w) { if (abs(cur.d[ro + x] - prev.d[po + x]) <= 14f) agree++; n++; x += 3 }
                y += 2
            }
            if (n == 0 || agree < 0.72 * n) return 0
            // must beat "no motion" clearly
            var agree0 = 0; var n0 = 0
            y = top
            while (y < bot) {
                val o = y * w; var x = 0
                while (x < w) { if (abs(cur.d[o + x] - prev.d[o + x]) <= 14f) agree0++; n0++; x += 3 }
                y += 2
            }
            if (n0 > 0 && agree.toDouble() / n < agree0.toDouble() / n0 + 0.10) return 0
            return bestDy
        }
    }
}
