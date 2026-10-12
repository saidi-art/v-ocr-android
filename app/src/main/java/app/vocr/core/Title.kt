package app.vocr.core

/** File name for the extracted text: the first meaningful sentence of the content. */
object Title {
    private val bad = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private val time = Regex("^\\d{1,2}[:.]\\d{2}(\\s?[ap]m)?$", RegexOption.IGNORE_CASE)

    fun from(text: String, fallback: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(40)
        fun ok(l: String, minChars: Int, minWords: Int): Boolean {
            if (l.startsWith("http", true) || time.matches(l)) return false
            val letters = l.count { it.isLetter() }
            return l.length >= minChars && l.split(Regex("\\s+")).size >= minWords && letters >= l.length * 0.6
        }
        // strict first (skips short app chrome like "Tools" or "G Grounding with Go"), then looser
        val pick = lines.firstOrNull { ok(it, 25, 4) } ?: lines.firstOrNull { ok(it, 12, 2) } ?: lines.firstOrNull { it.count { c -> c.isLetter() } >= 4 }
        val clean = (pick ?: fallback).replace(bad, " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ', '-')
        val cut = if (clean.length <= 60) clean else clean.substring(0, 60).substringBeforeLast(' ', clean.substring(0, 60))
        return cut.ifBlank { fallback }
    }
}
