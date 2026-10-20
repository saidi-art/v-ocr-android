package app.vocr.core

/**
 * Honest flags on the final OCR text: nothing is re-read and nothing is guessed into place.
 * Each flag only inserts a bracketed note next to text that looks like it lost information, so
 * the reader knows where the read may be incomplete. Pure functions over the finished lines:
 * zero cost on a clean capture (clean text passes through unchanged).
 */
object Flags {

    /** Appended once to the text of a single-frame capture: what one still screenshot can hide. */
    const val COMPOSER_NOTE = "[ScrollRSI: text behind the on-screen composer or chat box may be missing from this screenshot]"

    private const val URL_WRAP_NOTE = "[ScrollRSI: a link wraps across this line break - characters may be missing where it joins]"

    private fun isMark(l: String) = l.startsWith("[ScrollRSI:")

    // A real currency symbol that survived, or a letter-shaped one ('E200'-style: the sign read as a letter).
    private val symbolAmount = Regex("[$€£¥]\\s?\\d|\\d\\s?[$€£¥]")
    private val letterAmount = Regex("(?<![A-Za-z0-9])[ELY]\\d[\\d,]*(?:\\.\\d+)?(?![A-Za-z0-9])")
    // Amount shapes that lose all context when their symbol is dropped: thousands-grouped (30,394) or exact decimals (32,754.00).
    private val groupedAmount = Regex("(?<![\\w$€£¥.,])\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?(?![\\w$€£¥])")
    private val decimalAmount = Regex("(?<![\\w$€£¥.,])\\d+\\.\\d{2}(?![\\w$€£¥.,/])")
    private val currencyCode = Regex("(?:USD|EUR|GBP|JPY|AUD|CAD|CHF|NZD|SEK|NOK|DKK|CNY|HKD|SGD|KRW|INR|AED|SAR)\\s?$")

    private fun suspects(l: String, anchored: Boolean): List<String> {
        if (isMark(l)) return emptyList()
        val out = LinkedHashSet<String>()
        for (m in letterAmount.findAll(l)) out.add(m.value)
        if (anchored) {
            for (m in groupedAmount.findAll(l)) out.add(m.value)
            for (m in decimalAmount.findAll(l)) {
                if (currencyCode.containsMatchIn(l.substring(0, m.range.first))) continue   // 'USD 2.99' carries its currency as a code
                out.add(m.value)
            }
        }
        return out.toList()
    }

    /**
     * Inserts a note above every line holding an amount that likely lost its currency symbol:
     * 'E200'-style tokens always, bare grouped/decimal amounts when the page clearly deals in
     * money (a surviving symbol or a letter-shaped amount anchors it).
     */
    fun currency(lines: List<String>): List<String> {
        val anchored = lines.any { !isMark(it) && (symbolAmount.containsMatchIn(it) || letterAmount.containsMatchIn(it)) }
        if (!anchored) return lines
        val out = ArrayList<String>(lines.size + 4)
        for (l in lines) {
            val s = suspects(l, anchored)
            if (s.isNotEmpty()) out.add("[ScrollRSI: ${s.joinToString(", ") { "'$it'" }} may have lost a currency symbol - check the image]")
            out.add(l)
        }
        return out
    }

    private val urlChar = Regex("[A-Za-z0-9\\-._~%/?=&+#@:]+")
    private val urlShape = Regex("^(?:https?://|www\\.)|(?i:[a-z0-9-]+\\.(?:com|org|net|io|ai|app|dev|co|me|info|xyz|tv|gov|edu|jp|uk|de|fr|au|ca|nz|kr|cn|in)(?:[/._~%?=&+#-]|$))")
    private val urlPunct = Regex("[./_%~?=&-]")

    /** A link split by a line break: the left token ends mid-address and the right one continues it. */
    private fun wraps(a: String, b: String): Boolean {
        if (isMark(a) || isMark(b)) return false
        val ta = a.trimEnd().substringAfterLast(' ')
        val tb = b.trimStart().substringBefore(' ')
        if (ta.length < 3 || tb.isEmpty()) return false
        if (!ta.matches(urlChar) || !tb.matches(urlChar)) return false
        if (!urlShape.containsMatchIn(ta + tb)) return false
        return urlPunct.containsMatchIn(ta) || urlPunct.containsMatchIn(tb)
    }

    /** Marks every line break that cuts through a link: wrapped characters (underscores above all) are easy to lose at the join. */
    fun urlWraps(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size + 2)
        for (i in lines.indices) {
            out.add(lines[i])
            if (i + 1 < lines.size && wraps(lines[i], lines[i + 1])) out.add(URL_WRAP_NOTE)
        }
        return out
    }

    /** Drops a line that repeats the one right before it (a seam/overlap echo). Word-sized repeats stay: people do write "ha" twice. */
    fun dedupe(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        for (l in lines) if (l.length < 8 || out.lastOrNull() != l) out.add(l)
        return out
    }

    /** The flags applied to the finished text of one save, in reading order. */
    fun apply(lines: List<String>, singleFrame: Boolean): List<String> {
        var out = dedupe(lines)
        out = currency(out)
        out = urlWraps(out)
        if (singleFrame && out.isNotEmpty()) out = out + COMPOSER_NOTE
        return out
    }
}
