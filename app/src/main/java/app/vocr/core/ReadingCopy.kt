package app.vocr.core

/** Optional "Clean copy": a reading version of a saved text. Never changes a word or a digit; only layout (soft line wraps, stray spaces, blank-line runs). The raw file is never touched. */
object ReadingCopy {
    private val spaceBefore = Regex(" +([,.;:!?])(?=\\s|$)")
    private val multi = Regex("[ \\t]{2,}")

    fun make(raw: String): String {
        val lines = raw.replace("\r\n", "\n").split("\n").map { multi.replace(it.trimEnd(), " ").let { s -> spaceBefore.replace(s, "$1") } }
        val out = ArrayList<String>()
        for (l in lines) {
            if (l.isBlank()) { if (out.isNotEmpty() && out.last().isNotEmpty()) out.add(""); continue }
            val prev = out.lastOrNull()
            // soft wrap: the previous line did not end a sentence and this one continues in lower case
            if (prev != null && prev.isNotEmpty() && l.first().isLowerCase() && prev.last() !in ".!?:;\u2026\"" && !prev.endsWith("-")) {
                out[out.size - 1] = prev + " " + l.trimStart()
            } else out.add(l)
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out.joinToString("\n") + "\n"
    }
}
