package app.vocr.core

/** Repairs words the pixel-gap heuristic split inside narrow letters ("l imits", "i nstinct"), using a small EN+FR word list. */
class Spacing(private val dict: Set<String>) {
    private val validSingle = setOf("a", "A", "I", "\u00e0", "y", "O")
    private val alpha = Regex("^[A-Za-z\u00c0-\u00ff]+$")
    private val core = Regex("^([A-Za-z\u00c0-\u00ff]+)((?:['\u2019][a-z]+)?[^A-Za-z\u00c0-\u00ff]*)$")

    private fun isWord(w: String) = dict.contains(w.lowercase())

    fun fix(line: String): String {
        val toks = line.split(' ')
        if (toks.size < 2) return line
        val out = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            var cur = toks[i]
            while (i + 1 < toks.size && alpha.matches(cur) && cur.length <= 2) {
                val m = core.matchEntire(toks[i + 1]) ?: break
                val b = m.groupValues[1]; val tail = m.groupValues[2]
                val joined = cur + b
                val aBad = !(cur in validSingle) && !(cur.length == 2 && isWord(cur))
                val bBad = !isWord(b)
                if (isWord(joined) && (aBad || bBad)) { cur = joined + tail; i++ } else break
            }
            out.add(cur); i++
        }
        return out.joinToString(" ")
    }

    companion object {
        fun load(lines: List<String>) = Spacing(lines.filter { it.isNotEmpty() }.toHashSet())
    }
}
