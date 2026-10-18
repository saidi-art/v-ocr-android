package app.vocr.capture

/**
 * Where a scroll-capture drag may start and end. Chat pages keep a text box at the bottom: a drag that starts on it does not scroll the page,
 * and the system bars and the edge-gesture strips swallow drags too. All values are fractions of the screen, taken from a 1080x2340 screen
 * (y 150..1700 px, x 80..1000 px, drag length 600..1500 px) so other screens scale with them.
 */
object SwipeZone {
    const val Y_MIN = 150f / 2340f
    const val Y_MAX = 1700f / 2340f
    const val X_MIN = 80f / 1080f
    const val X_MAX = 1000f / 1080f
    const val LEN_MIN = 600f / 2340f
    const val LEN_MAX = 1500f / 2340f

    /** The zone's bottom: the fixed limit, or higher when a footer (navigation bar, pinned bar) takes more room. */
    fun bottom(h: Float, footerPx: Int): Float = minOf(Y_MAX, (h - footerPx) / h - 0.02f).coerceAtLeast(Y_MIN + LEN_MIN)

    private fun len(want: Float, bottom: Float) = want.coerceIn(LEN_MIN, LEN_MAX).coerceAtMost(bottom - Y_MIN)

    /** A drag with the finger moving up, centred near [mid], both ends inside the zone. Returns start y, end y. */
    fun forward(mid: Float, want: Float, bottom: Float): Pair<Float, Float> {
        val l = len(want, bottom)
        val c = mid.coerceIn(Y_MIN + l / 2f, bottom - l / 2f)
        return Pair(c + l / 2f, c - l / 2f)
    }

    /** A drag with the finger moving down (restores the overlap), centred in the zone. Returns start y, end y. */
    fun back(want: Float, bottom: Float): Pair<Float, Float> {
        val l = len(want, bottom)
        val c = (Y_MIN + bottom) / 2f
        return Pair(c - l / 2f, c + l / 2f)
    }

    fun x(f: Float): Float = f.coerceIn(X_MIN, X_MAX)
}
