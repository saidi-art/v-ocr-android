package app.vocr.core

/**
 * Direct on-screen text (experimental, opt-in, off by default): text read straight from the accessibility tree instead of OCR.
 * Frames overlap when scrolling, so naive joining would duplicate lines. [merge] overlaps each frame's lines on the accumulated
 * text the way the image stitcher overlaps pixels; a join it cannot prove is marked, never hidden. Pure, unit-tested.
 */
object DirectText {
    /** The largest k where the last k lines of [acc] equal the first k lines of [next]; 0 = no proven overlap. */
    fun overlap(acc: List<String>, next: List<String>): Int {
        val cap = minOf(acc.size, next.size)
        for (k in cap downTo 1) {
            var ok = true
            for (i in 0 until k) if (acc[acc.size - k + i] != next[i]) { ok = false; break }
            if (ok) return k
        }
        return 0
    }

    /**
     * Joins per-capture line lists into one text. A proven overlap is merged silently; an unproven join keeps all lines and
     * marks the spot ("[join not proven]"), the direct-text equivalent of the image splitter's honest gap.
     */
    fun merge(frames: List<List<String>>): List<String> {
        val out = ArrayList<String>()
        var firstFrame = true
        for (f in frames) {
            val lines = f.map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            if (firstFrame) { out.addAll(lines); firstFrame = false; continue }
            val k = overlap(out, lines)
            if (k == 0) { out.add("[ScrollRSI direct text: join not proven here - lines around this mark may repeat or be missing]"); out.addAll(lines) }
            else out.addAll(lines.drop(k))
        }
        return out
    }
}
