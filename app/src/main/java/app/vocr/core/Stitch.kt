package app.vocr.core

import kotlin.math.max
import kotlin.math.min

/** Text-level stitching: removes the lines two neighbouring strips have in common. */
object Stitch {
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

    /** Merge per-strip line lists (top to bottom) into one list. */
    fun merge(strips: List<List<String>>, maxSkip: Int = 6): List<String> {
        val acc = ArrayList<String>()
        for (lines in strips) {
            if (lines.isEmpty()) continue
            if (acc.isEmpty()) { acc.addAll(lines); continue }
            var done = false
            val kMax = min(acc.size, lines.size)
            loop@ for (k in kMax downTo 1) {
                for (j in 0..min(maxSkip, lines.size - k)) {
                    var good = true; var chars = 0
                    for (i in 0 until k) {
                        val s = sim(acc[acc.size - k + i], lines[j + i])
                        if (s < OK) { good = false; break }
                        chars += norm(lines[j + i]).length
                    }
                    if (!good || (k < 2 && chars < 12) || (k == 2 && chars < 10)) continue
                    // keep the longer version of the edge lines (a strip edge may cut a line)
                    if (norm(lines[j]).length > norm(acc[acc.size - k]).length) acc[acc.size - k] = lines[j]
                    val li = j + k - 1
                    if (norm(lines[li]).length > norm(acc[acc.size - 1]).length) acc[acc.size - 1] = lines[li]
                    for (i in j + k until lines.size) acc.add(lines[i])
                    done = true; break@loop
                }
            }
            if (!done) acc.addAll(lines)
        }
        return acc
    }
}
