package app.vocr

import app.vocr.capture.OverlayTracker
import app.vocr.capture.ShotMatch
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** A chat page with a white fade and a round "jump to latest" button above a floating composer, drawn from scratch. */
class SeamFadeTest {
    private val W = 720; private val H = 1560; private val NAV = 90; private val PILL_H = 380
    private val PILL_TOP = H - NAV - PILL_H
    private val FADE_ROWS = 100
    private val PH = 14000
    private val page = ByteArray(W * PH).also { pg ->
        val rng = Random(7)
        var y = 40
        while (y < PH - 200) {
            val bh = 60 + rng.nextInt(240); val bx = if (rng.nextBoolean()) 40 else 200; val bw = 300 + rng.nextInt(200)
            val shade = 40 + rng.nextInt(60)
            for (yy in y until minOf(PH, y + bh)) for (x in bx until minOf(W, bx + bw)) {
                val text = (yy - y) % 26 in 8..17 && x > bx + 14 && x < bx + bw - 14 && ((x / 5 + yy / 3 + (x * yy) % 7) % 3 != 0)
                pg[yy * W + x] = (if (text) 200 else shade).toByte()
            }
            y += bh + 30 + rng.nextInt(90)
        }
    }

    private fun frame(off: Int): ByteArray {
        val f = ByteArray(W * H)
        for (y in 0 until H - NAV) System.arraycopy(page, (off + y) * W, f, y * W, W)
        for (y in H - NAV until H) for (x in 0 until W) f[y * W + x] = 15
        // composer
        for (y in PILL_TOP until H - NAV) for (x in 30 until W - 30) {
            val py = y - PILL_TOP
            var v = 244
            if (py < 3 || py >= PILL_H - 3) v = 255
            if (py in 70..100 && x in 70..330 && ((x / 4 + py) % 3 != 0)) v = 40
            f[y * W + x] = v.toByte()
        }
        // fade to white over the last rows of the chat, and the round button on it
        for (i in 0 until FADE_ROWS) { val y = PILL_TOP - FADE_ROWS + i; val a = (i * 100) / FADE_ROWS
            for (x in 0 until W) { val u = f[y * W + x].toInt() and 255; f[y * W + x] = ((u * (100 - a) + 255 * a) / 100).toByte() } }
        val cy = PILL_TOP - 50
        for (y in cy - 28..cy + 28) for (x in 332..388) if ((x - 360) * (x - 360) + (y - cy) * (y - cy) < 28 * 28) f[y * W + x] = (if ((x + y) % 9 < 2) 40 else 235).toByte()
        return f
    }

    private val offs = (0 until 9).map { 300 + it * 640 }

    /** Stitches like LongShotSession.addFrame; fade = rows left out of each append (0 = the old behaviour). */
    private fun stitch(fade: Int): Pair<ByteArray, Int> {
        val t = OverlayTracker(W, H, 0, NAV)
        val out = java.io.ByteArrayOutputStream(); var written = 0
        t.update(frame(offs[0]), frame(offs[1]), offs[1] - offs[0])
        var prev = frame(offs[1]); var ftrPrev = -1; var ftrBasePrev = 0; var stickyFtr = 0
        fun put(src: ByteArray, a: Int, b: Int) { if (b > a) { out.write(src, a * W, (b - a) * W); written += b - a } }
        for (i in 2 until offs.size) {
            val cur = frame(offs[i])
            val m = ShotMatch.find(prev, cur, W, H, 0, 2, 0, maxOf(NAV, t.footer()))
            assertEquals("pair $i dy=${m.dy} score=${m.score} still=${m.still}", ShotMatch.Status.OK, m.status); assertEquals(offs[i] - offs[i - 1], m.dy)
            t.update(prev, cur, m.dy)
            val ftr = maxOf(m.ftr, stickyFtr, t.footer()); stickyFtr = ftr
            val bottom = H - ftr
            if (ftrPrev < 0) { put(prev, 0, maxOf(0, bottom - fade)); ftrPrev = H - maxOf(0, bottom - fade); ftrBasePrev = ftr }
            val sm = ShotMatch.seamFade(H, ftrPrev, ftr, m.dy, 0, fade, ftrBasePrev)
            if (sm[4] > sm[3]) put(prev, sm[3], sm[4])
            put(cur, sm[0], sm[1])
            ftrPrev = sm[2]; ftrBasePrev = ftr; prev = cur
        }
        return Pair(out.toByteArray(), written)
    }

    private fun badRows(img: ByteArray, written: Int): Int {
        var bad = 0
        for (y in 0 until written) { for (x in 0 until W) if (img[y * W + x] != page[(offs[1] + y) * W + x]) { bad++; break } }
        return bad
    }

    @Test fun oldSeamBakesTheFadeAndButtonIn() {
        val (img, n) = stitch(0)
        assertTrue("old behaviour should leave rows with the fade: bad=${badRows(img, n)}", badRows(img, n) > 50)
    }

    @Test fun newSeamUsesCleanRowsFromTheNextCapture() {
        val (img, n) = stitch(ShotMatch.FADE)
        assertTrue("written=$n", n > 3000)
        assertEquals("rows that differ from the page", 0, badRows(img, n))
    }

    @Test fun noRowIsLostWhenTheDragAlmostCoversTheBand() {
        // dy larger than the band minus the fade: rows of the previous capture are written so the page stays continuous
        val sm = ShotMatch.seamFade(1560, 1560 - (1560 - 90 - 380 - 120), 90 + 380, 1000, 0, 120, 90 + 380)
        assertTrue(sm[4] > sm[3]); assertEquals(0, sm[0])
        val ok = ShotMatch.seamFade(2340, 133 + 120, 713, 745, 228, 120, 713)
        assertEquals(h(2340, 253, 745), ok[0]); assertEquals(2340 - 713 - 120, ok[1]); assertEquals(ok[3], ok[4])
        assertEquals(2340 - ok[1], ok[2])
    }
    private fun h(h: Int, ftrPrev: Int, dy: Int) = h - ftrPrev - dy
}
