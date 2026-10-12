package app.vocr.core

import kotlin.math.max
import kotlin.math.min

/** Text-level stitching: removes the lines two neighbouring strips have in common. */
object Stitch {
    fun normKey(s: String) = norm(s)
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }; var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 0..1 similarity; a cut-off line (strip edge) that is contained in the full one counts as a match. */
    fun sim(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val shortS = if (x.length <= y.length) x else y; val longS = if (x.length <= y.length) y else x
        if (shortS.length >= 8 && longS.contains(shortS)) return 0.95
        return 1.0 - lev(x, y).toDouble() / max(x.length, y.length)
    }

    private const val OK = 0.80

    /**
     * Merge per-strip line lists (top to bottom) into one list.
     * Anchors = the longer lines of the new strip; each is looked up (in order) among the last lines already kept.
     * Everything up to the last anchor found there is overlap and is dropped; the rest is appended.
     * Junk lines between anchors do not break the match (unlike a strict block comparison).
     */
    fun merge(strips: List<List<String>>, window: Int = 70): List<String> {
        val acc = ArrayList<String>()
        for (lines in strips) {
            if (lines.isEmpty()) continue
            if (acc.isEmpty()) { acc.addAll(lines); continue }
            val lo = max(0, acc.size - window)
            var lastAcc = lo - 1; var lastNew = -1
            var k0 = -1; var j0 = -1
            for (j in lines.indices) {
                if (norm(lines[j]).length < 12) continue
                var bestK = -1; var bestS = 0.0
                for (k in lastAcc + 1 until acc.size) {
                    if (norm(acc[k]).length < 8) continue
                    if (k0 >= 0) {   // later matches must keep the same line offset as the first one (overlap is contiguous)
                        val dj = j - j0; val dk = k - k0
                        if (Math.abs(dk - dj) > 4 + 0.3 * dj) continue
                    }
                    val sc = sim(acc[k], lines[j])
                    if (sc >= 0.85 && sc > bestS) { bestS = sc; bestK = k; if (sc >= 0.99) break }
                }
                if (bestK >= 0) {
                    if (k0 < 0) { k0 = bestK; j0 = j }
                    lastAcc = bestK; lastNew = j
                }
            }
            // overlap must reach the end of what we already have (a few junk lines tolerated); otherwise it was a spurious match
            if (lastNew < 0 || acc.size - 1 - lastAcc > 8) { acc.addAll(lines); continue }
            for (j in 0..lastNew) { /* matched region: keep the longer edge versions */ }
            for (i in lastNew + 1 until lines.size) acc.add(lines[i])
        }
        return acc
    }
}
