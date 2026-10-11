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
class StripBuilder(
    private val simThreshold: Double = 0.95,
    private val minStripFrac: Double = 0.30,
    private val overlapPx: Int = 18,
) {
    var total = 0; private set
    var dup = 0; private set
    var full = 0; private set
    var cropped = 0; private set

    private var prevHash: Long? = null
    private var prev: Gray? = null
    private var acc = 0
    private var vb = 0

    fun push(hash: Long, g: Gray): Decision {
        total++
        val p = prev
        val ph = prevHash
        if (p != null && ph != null && Hash.similarity(hash, ph) > simThreshold && meanAbsDiff(g, p) < 2.0f) {
            dup++; return Decision.Skip   // keep prev as reference; nothing changed
        }
        var out: Decision = Decision.Skip
        if (p == null) {
            full++; out = Decision.Full
        } else {
            val dy = detectScroll(p, g)
            if (dy == 0) {
                full++; acc = 0; out = Decision.Full
            } else {
                acc += dy
                vb = g.h - footerHeight(p, g)
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

        /** dy>0 when content moved UP by dy rows (scrolling down); 0 if no clear match. */
        fun detectScroll(prev: Gray, cur: Gray): Int {
            val h = cur.h; val w = cur.w
            val top = (h * 0.25).toInt(); val bot = (h * 0.75).toInt()
            val maxdy = (h * 0.7).toInt()
            val step = 2 // sample every 2nd column for speed
            fun err(dy: Int): Float {
                var s = 0.0; var n = 0
                for (y in top until bot) {
                    val ro = y * w; val po = (y + dy) * w
                    var x = 0
                    while (x < w) { s += abs(cur.d[ro + x] - prev.d[po + x]); n++; x += step }
                }
                return (s / n).toFloat()
            }
            val base = err(0)
            var best = Float.MAX_VALUE; var bestDy = 0
            for (dy in 2 until maxdy) {
                if (bot + dy > h) break
                val e = err(dy)
                if (e < best) { best = e; bestDy = dy }
            }
            return if (best < 6.0f && best < base * 0.6f) bestDy else 0
        }

        /** Rows at the bottom identical between frames = fixed footer. */
        fun footerHeight(prev: Gray, cur: Gray): Int {
            val h = cur.h; val w = cur.w
            var n = 0
            while (n < (h * 0.25).toInt()) {
                val y = h - 1 - n; var s = 0.0
                for (x in 0 until w) s += abs(cur.d[y * w + x] - prev.d[y * w + x])
                if (s / w >= 1.0) break
                n++
            }
            return n
        }
    }
}
