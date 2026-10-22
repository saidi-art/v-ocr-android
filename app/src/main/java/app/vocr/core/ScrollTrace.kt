package app.vocr.core

/**
 * How far the page really moved during one of OUR OWN screen recordings, from the phone's scroll events
 * (their delta/position numbers only, never content). Recorded live by the capture service while the user scrolls,
 * saved next to the video ("rec-....mp4.trace"), and used when the video is read: a strip's overlap with the
 * previous strip is then proven, not guessed, so only the provably-new rows are read.
 *
 * Doubt rule (the owner prefers repeats over omissions, Oct 2026): any gap in the evidence - no events from this
 * app, a re-anchor jump, a time outside the event-active window - makes pxBetween return -1 and the reader falls
 * back to whole strips, exactly the pre-trace behavior.
 *
 * Pure, unit-tested. Times are milliseconds of video time (pauses excluded, matching the recorded timeline).
 */
class ScrollTrace(val screenH: Int) {
    private val snaps = ArrayList<LongArray>()   // [tMs, cumPx, segment]
    private val eventTs = ArrayList<Long>()
    private var seg = 0

    /** One poll of the signal: cumulative px of page movement since the (re-)arm, in device pixels. */
    @Synchronized fun add(tMs: Long, cumPx: Int) {
        if (cumPx < 0) return
        snaps.add(longArrayOf(tMs, cumPx.toLong(), seg.toLong()))
    }

    /** An event arrived at this time (poll resolution is fine). */
    @Synchronized fun event(tMs: Long) { eventTs.add(tMs) }

    /** The position signal jumped mid-gesture (list re-anchor): the cumulative line breaks here and re-bases to 0. */
    @Synchronized fun reanchor(tMs: Long) {
        seg++
        snaps.add(longArrayOf(tMs, 0L, seg.toLong()))
    }

    @Synchronized fun events(): Int = eventTs.size

    /** The window in which "no events" really means "the page was still": around the observed event flow. */
    @Synchronized private fun window(): LongArray? {
        if (eventTs.size < MIN_EVENTS) return null
        return longArrayOf(eventTs.first() - LEAD_MS, eventTs.last() + TAIL_MS)
    }

    /**
     * The proven page movement between two video times, in device pixels; -1 when not provable.
     * Both times must sit inside the event-active window and inside one unbroken segment.
     */
    @Synchronized fun pxBetween(t1Ms: Long, t2Ms: Long): Int {
        val win = window() ?: return -1
        if (t1Ms < win[0] || t2Ms > win[1] || t2Ms < t1Ms) return -1
        val a = at(t1Ms) ?: return -1
        val b = at(t2Ms) ?: return -1
        if (a[1] != b[1]) return -1   // a re-anchor broke the line between the two times
        return (b[0] - a[0]).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Latest snapshot at or before t as [cumPx, segment], or null before the first snapshot. */
    private fun at(tMs: Long): LongArray? {
        var lo = 0; var hi = snaps.size - 1; var ans = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (snaps[mid][0] <= tMs) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
        if (ans < 0) return null
        val s = snaps[ans]
        return longArrayOf(s[1], s[2])
    }

    @Synchronized fun serialize(): String {
        val sb = StringBuilder()
        sb.append("H ").append(screenH).append('\n')
        for (t in eventTs) sb.append("E ").append(t).append('\n')
        for (s in snaps) sb.append("S ").append(s[0]).append(' ').append(s[1]).append(' ').append(s[2]).append('\n')
        return sb.toString()
    }

    companion object {
        const val MIN_EVENTS = 5
        const val LEAD_MS = 1000L
        const val TAIL_MS = 1500L

        fun parse(text: String): ScrollTrace? {
            val lines = text.lines().filter { it.isNotBlank() }
            val head = lines.firstOrNull { it.startsWith("H ") } ?: return null
            val h = head.removePrefix("H ").trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
            val tr = ScrollTrace(h)
            var maxSeg = 0
            for (l in lines) {
                when {
                    l.startsWith("E ") -> l.removePrefix("E ").trim().toLongOrNull()?.let { tr.eventTs.add(it) }
                    l.startsWith("S ") -> {
                        val p = l.removePrefix("S ").trim().split(' ')
                        if (p.size == 3) {
                            val t = p[0].toLongOrNull(); val c = p[1].toLongOrNull(); val sg = p[2].toIntOrNull()
                            if (t != null && c != null && sg != null) { tr.seg = sg; maxSeg = maxOf(maxSeg, sg); tr.snaps.add(longArrayOf(t, c, sg.toLong())) }
                        }
                    }
                }
            }
            tr.seg = maxSeg
            return tr
        }
    }
}
