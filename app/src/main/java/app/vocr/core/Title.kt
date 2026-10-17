package app.vocr.core

/** File name for the extracted text: "<context header> - <first meaningful line>". */
object Title {
    private val bad = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private val time = Regex("^\\d{1,2}[:.]\\d{2}(\\s?[ap]m)?$", RegexOption.IGNORE_CASE)
    private val chromeWords = setOf("tools", "message", "search", "back", "menu", "home", "share", "copy", "more", "you", "today", "yesterday")

    private fun clean(s: String) = s.replace(bad, " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ', '-')

    private fun cut(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max).substringBeforeLast(' ', s.substring(0, max)).trim()

    /** Share of the line's words (letters only, 2+ chars) found in the dictionary; 1.0 when there is nothing to judge. */
    private fun cleanliness(l: String, dict: Set<String>): Double {
        val toks = l.lowercase().split(Regex("[^a-z\\u00e0-\\u00ff]+")).filter { it.length >= 2 }
        return if (toks.isEmpty()) 1.0 else toks.count { it in dict }.toDouble() / toks.size
    }

    private val sizeRe = Regex("(?i)^\\d+([.,]\\d+)?\\s?(b|kb|mb|gb|items?|files?)$")
    private val dateRe = Regex("(?i)^(\\d{1,2}\\s\\w{3,9}(\\s\\d{1,2}:\\d{2}(\\s?[ap]m)?)?|\\d{4}-\\d{2}-\\d{2}.*|(today|yesterday)\\b.*)$")
    private val fileRe = Regex("(?i)^\\S+\\.(apk|pdf|png|jpe?g|mp4|txt|csv|zip|docx?|xlsx?|json|html?|pptx?|mp3)$")
    /** True when the top of the text looks like a file or folder list (names, sizes, dates) rather than a page: a title taken from it would be a file name. */
    fun looksLikeList(text: String): Boolean {
        val top = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(40)
        val hits = top.count { sizeRe.matches(it) || dateRe.matches(it) || fileRe.matches(it) }
        return hits >= 5
    }

    fun from(text: String, fallback: String, dict: Set<String>? = null): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(40)
        fun ok(l: String, minChars: Int, minWords: Int): Boolean {
            if (l.startsWith("http", true) || time.matches(l)) return false
            val letters = l.count { it.isLetter() }
            return l.length >= minChars && l.split(Regex("\\s+")).size >= minWords && letters >= l.length * 0.6
        }
        // strict first (skips short app chrome like "Tools" or "G Grounding with Go"), then looser
        var ci = lines.indexOfFirst { ok(it, 25, 4) }
        if (ci >= 0 && dict != null && cleanliness(lines[ci], dict) < 0.8) {
            // the first candidate looks garbled (fast mode, odd fonts): take the cleanest candidate (earliest on ties)
            val cands = lines.indices.filter { ok(lines[it], 25, 4) }
            val top = cands.maxOfOrNull { cleanliness(lines[it], dict) } ?: 0.0
            val best = cands.firstOrNull { cleanliness(lines[it], dict) >= top - 0.01 }
            if (best != null) ci = best
        }
        if (ci < 0) ci = lines.indexOfFirst { ok(it, 12, 2) }
        if (ci < 0) ci = lines.indexOfFirst { it.count { c -> c.isLetter() } >= 4 }
        if (ci < 0) return fallback
        val content = clean(lines[ci])
        // context header: the nearest short name-like line just above the content (e.g. the chat or page title)
        val header = (ci - 1 downTo maxOf(0, ci - 8)).map { lines[it] }.firstOrNull { l ->
            val letters = l.count { it.isLetter() }
            l.length in 3..24 && letters >= l.length * 0.8 && l.split(Regex("\\s+")).size <= 3 &&
                !time.matches(l) && l.lowercase() !in chromeWords && !l.startsWith("http", true)
        }?.let { clean(it) }?.takeIf { it.length >= 3 && !content.startsWith(it, true) }
        val full = if (header != null) cut("$header - $content", 60) else cut(content, 60)
        val bare = full.replace(Regex("(?i)\\.(html?|php|aspx?|xhtml|pdf|txt|mp4|png|jpe?g)\\s*$"), "").trimEnd()
        return bare.ifBlank { fallback }
    }
}
