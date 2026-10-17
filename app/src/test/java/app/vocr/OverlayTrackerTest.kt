package app.vocr

import app.vocr.capture.OverlayTracker
import app.vocr.capture.ShotMatch
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** A floating glass composer over a moving chat page, drawn from scratch (no real screen content). */
class OverlayTrackerTest {
    private val W = 720; private val H = 1560; private val NAV = 90; private val PILL_H = 190
    private val PILL_TOP = H - NAV - PILL_H
    private val rng = Random(21)
    private val PH = 12000
    private val page = ByteArray(W * PH).also { pg ->
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

    private fun frame(off: Int, pill: Boolean, arrow: Boolean = false): ByteArray {
        val f = ByteArray(W * H)
        for (y in 0 until H - NAV) System.arraycopy(page, (off + y) * W, f, y * W, W)
        for (y in H - NAV until H) for (x in 0 until W) f[y * W + x] = 15
        if (pill) for (y in PILL_TOP until H - NAV) for (x in 30 until W - 30) {
            val py = y - PILL_TOP
            val under = f[y * W + x].toInt() and 255
            var v = (under * 20 + 238 * 80) / 100                          // glass: 20% of the page shows through
            if (py < 3 || py >= PILL_H - 3) v = 255                          // border
            if (py in 70..100 && x in 70..330 && ((x / 4 + py) % 3 != 0)) v = 40   // "Reply to ..." text
            if (py in 140..178 && x in 60..110) v = 30                         // plus button
            if (py in 140..178 && x in W - 150..W - 80) v = 25                 // mic button
            f[y * W + x] = v.toByte()
        }
        if (arrow) for (y in PILL_TOP - 90 until PILL_TOP - 30) for (x in 330 until 390) if ((x - 360) * (x - 360) + (y - (PILL_TOP - 60)) * (y - (PILL_TOP - 60)) < 28 * 28) f[y * W + x] = (if ((x + y) % 9 < 2) 40 else 235).toByte()
        return f
    }

    private val offs = intArrayOf(300, 300 + 560, 300 + 1135, 300 + 1700, 300 + 2285)

    @Test fun findsAFloatingGlassComposer() {
        val t = OverlayTracker(W, H, 0, NAV)
        for (i in 1 until offs.size) assertTrue(t.update(frame(offs[i - 1], true), frame(offs[i], true), offs[i] - offs[i - 1]))
        val f = t.footer()
        assertTrue("footer=$f pill from the bottom=${H - PILL_TOP}", f in (H - PILL_TOP - 4)..(H - PILL_TOP + 30))
        assertEquals(0, t.header())
    }

    @Test fun oneClearPairIsEnough() {
        val t = OverlayTracker(W, H, 0, NAV)
        t.update(frame(1000, true), frame(1830, true), 830)
        assertTrue("footer=${t.footer()}", t.footer() in (H - PILL_TOP - 4)..(H - PILL_TOP + 30))
    }

    @Test fun nothingFoundOnAPlainMovingPage() {
        val t = OverlayTracker(W, H, 0, NAV)
        for (i in 1 until offs.size) t.update(frame(offs[i - 1], false), frame(offs[i], false), offs[i] - offs[i - 1])
        assertEquals(0, t.footer()); assertEquals(0, t.header())
    }

    @Test fun smallScrollsGiveNoEvidence() {
        val t = OverlayTracker(W, H, 0, NAV)
        assertFalse(t.update(frame(1000, true), frame(1020, true), 20))
        assertEquals(0, t.footer())
    }

    @Test fun stitchedImageHasNoComposerAndNoLostRows() {
        val t = OverlayTracker(W, H, 0, NAV)
        val out = java.io.ByteArrayOutputStream()
        // The first pair is learned from the known offset (in the app that pair may be a MISMATCH while the composer pollutes the match; the learned bar then helps the later pairs).
        t.update(frame(offs[0], true), frame(offs[1], true), offs[1] - offs[0])
        var prev = frame(offs[1], true, false); var ftrPrev = -1; var written = 0
        var stickyFtr = 0
        for (i in 2 until offs.size) {
            val cur = frame(offs[i], true, false)   // the small floating arrow circle is not handled by this step
            val m = ShotMatch.find(prev, cur, W, H, 0, 2, 0, maxOf(NAV, t.footer()))
            assertEquals("pair $i dy=${m.dy} score=${m.score} still=${m.still} hdr=${m.hdr} ftr=${m.ftr}", ShotMatch.Status.OK, m.status); assertEquals(offs[i] - offs[i - 1], m.dy)
            t.update(prev, cur, m.dy)
            val ftr = maxOf(m.ftr, stickyFtr, t.footer()); stickyFtr = ftr
            if (ftrPrev < 0) { out.write(prev, 0, (H - ftr) * W); written += H - ftr; ftrPrev = ftr }
            val sm = ShotMatch.seam(H, ftrPrev, ftr, m.dy, 0)
            if (H - ftr > sm[0]) { out.write(cur, sm[0] * W, (H - ftr - sm[0]) * W); written += H - ftr - sm[0] }
            ftrPrev = sm[2]; prev = cur
        }
        val img = out.toByteArray()
        assertEquals(written * W, img.size)
        var bad = 0
        for (y in 0 until written) for (x in 0 until W) if (img[y * W + x] != page[(offs[1] + y) * W + x]) { bad++; break }
        assertEquals("rows that differ from the page: $bad of $written (the composer or the arrow was baked in)", 0, bad)
        assertTrue("written=$written", written > 2000)
    }

    /** Local only: run the tracker on a real screenshot pair. Set VOCR_REAL_A and VOCR_REAL_B to two raw grey files; skipped in CI. */
    @Test fun realFramesOptional() {
        val pa = System.getenv("VOCR_REAL_A") ?: return
        val pb = System.getenv("VOCR_REAL_B") ?: return
        val dy = (System.getenv("VOCR_REAL_DY") ?: return).toInt()
        val a = java.io.File(pa).readBytes(); val b = java.io.File(pb).readBytes()
        val w = (System.getenv("VOCR_REAL_W") ?: "1080").toInt(); val h = a.size / w
        val t = OverlayTracker(w, h, 97, 135)
        t.update(a, b, dy)
        println("REAL footer=${t.footer()} header=${t.header()}")
        assertTrue(t.footer() > 0)
    }
}
