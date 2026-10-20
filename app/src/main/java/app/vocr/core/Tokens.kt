package app.vocr.core

import kotlin.math.max
import kotlin.math.min

/**
 * Structured-token rescue. Emails, links, dates and numbers are the tokens a capture is kept for, and one wrong
 * character silently breaks a paste. Two conservative layers:
 *  - [fixLine]: deterministic digit<->letter repairs inside a closed word shape (month names only, "0ct" -> "Oct").
 *    Only a token that cannot be real text is touched; anything open-class (emails, links) is left to the pixels.
 *  - [repairLine]: when a line holds a broken shaped token, that line's pixels are re-read at a few raster phases,
 *    and a token is swapped only when at least two independent re-reads agree on the same valid replacement.
 *    Shape-valid guesses are never treated as proven text: without pixel agreement the original reading stays.
 * Everything else is left alone: the saved text stays the faithful reading.
 */
object Tokens {
    private val emailOk = Regex("^[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+\\.)+[A-Za-z]{2,}$")
    private val emailish = Regex("^[A-Za-z0-9._%+@-]*@[A-Za-z0-9._%,-]*$")
    private val commaTld = Regex("^[A-Za-z0-9-]+,[A-Za-z]{2,3}$")   // "github,com": a dot read as a comma
    private val monthNames = mapOf(
        "january" to "January", "february" to "February", "march" to "March", "april" to "April", "may" to "May",
        "june" to "June", "july" to "July", "august" to "August", "september" to "September", "october" to "October",
        "november" to "November", "december" to "December",
        "jan" to "Jan", "feb" to "Feb", "mar" to "Mar", "apr" to "Apr", "jun" to "Jun", "jul" to "Jul",
        "aug" to "Aug", "sep" to "Sep", "sept" to "Sept", "oct" to "Oct", "nov" to "Nov", "dec" to "Dec")
    private val digitToLetter = mapOf('0' to 'o', '1' to 'l', '5' to 's', '8' to 'b', '6' to 'g', '2' to 'z')
    /** Raster heights for the second-look reads: the recognizer input height is fixed, so each phase feeds it a genuinely different raster. */
    private val PHASES = intArrayOf(56, 72, 88)
    private const val LEAD = "([{<'\""
    private const val TAIL = ")]}>!?:;,'\""

    private class Core(val pre: String, val core: String, val post: String)
    /** Splits surrounding punctuation off a token so the shape test sees "0ct" in "(0ct" and "github.com" in "github.com,". */
    private fun split(t: String): Core {
        var a = 0; var b = t.length
        while (a < b && LEAD.indexOf(t[a]) >= 0) a++
        while (b > a && TAIL.indexOf(t[b - 1]) >= 0) b--
        return Core(t.substring(0, a), t.substring(a, b), t.substring(b))
    }

    /** The canonical month name when the token is a month with digit(s) misread as letter-lookalikes ("0ct" -> "Oct"), else null. */
    private fun monthSwap(c: String): String? {
        if (c.length !in 3..9 || !c.any { it.isDigit() } || !c.all { it.isLetter() || it.isDigit() }) return null
        val sw = StringBuilder(c.length)
        for (ch in c) sw.append(if (ch.isDigit()) digitToLetter[ch] ?: return null else ch.lowercaseChar())
        return monthNames[sw.toString()]
    }

    /** True when a token looks like a broken email, link, date or number: worth one pixel re-read of its line. */
    fun malformed(tok: String): Boolean {
        val c = split(tok).core
        if (c.length < 3) return false
        if (c.indexOf('@') >= 0) return c.count { it == '@' } == 1 && emailish.matches(c) && !emailOk.matches(c)
        if (monthSwap(c) != null) return true
        if (commaTld.matches(c)) return true
        return false
    }

    /** Deterministic pass over a finished line: month names only, the one closed shape where a digit can never be intended. */
    fun fixLine(line: String): String = line.split(' ').joinToString(" ") { t ->
        val p = split(t)
        if (p.core.length >= 3) monthSwap(p.core)?.let { return@joinToString p.pre + it + p.post }
        t
    }

    private fun edit(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = min(min(d[j] + 1, d[j - 1] + 1), prev + (if (a[i - 1] == b[j - 1]) 0 else 1))
                prev = t
            }
        }
        return d[b.length]
    }

    /** A candidate replacement token: valid shape, same edge punctuation, small edit distance from the first reading. */
    private fun candidate(old: String, new: String): Boolean {
        if (new == old || malformed(new)) return false
        val pa = split(old); val pb = split(new)
        if (pa.pre != pb.pre || pa.post != pb.post) return false
        return edit(pa.core, pb.core) <= max(2, pa.core.length / 4)
    }

    /**
     * Vote over independent re-reads (pure): a broken shaped token is replaced only when at least two re-reads agree
     * on the same valid replacement. Reads with a different word count are ignored; anything less than agreement
     * keeps the first reading.
     */
    fun chooseVote(old: List<String>, reads: List<List<String>>): List<String> {
        val usable = reads.filter { it.size == old.size }
        if (usable.isEmpty()) return old
        val out = old.toMutableList()
        for (i in old.indices) {
            if (!malformed(old[i])) continue
            val votes = usable.map { it[i] }.filter { candidate(old[i], it) }
            val win = votes.groupingBy { it }.eachCount().entries.maxByOrNull { it.value }
            if (win != null && win.value >= 2) out[i] = win.key
        }
        return out
    }

    /** Re-reads a line's pixels when it holds a broken shaped token; swaps in only what the vote accepts. */
    fun repairLine(eng: OcrEngine, img: Rgb, ln: OcrLine): OcrLine {
        val toks = ln.text.split(' ')
        if (toks.none { malformed(it) }) return ln   // no shaped token: no re-read, no cost
        val reads = ArrayList<List<String>>()
        for (ph in PHASES) eng.reRead(img, ln, ph)?.let { reads.add(it.text.split(' ')) }
        val out = chooseVote(toks, reads)
        return if (out == toks) ln else OcrLine(out.joinToString(" "), ln.x0, ln.y0, ln.x1, ln.y1, ln.conf)
    }
}
