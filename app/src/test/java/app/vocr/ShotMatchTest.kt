package app.vocr

import app.vocr.capture.ShotMatch
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class ShotMatchTest {
    private val W = 720; private val H = 1560; private val HDR = 110; private val FTR = 260
    private val rng = Random(11)
    // tall dark "chat": black background, sparse grey bubbles with noisy text lines
    private val PH = 14000
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
    private fun frame(off: Int, cursor: Int = 0): ByteArray {
        val f = ByteArray(W * H)
        for (y in HDR until H - FTR) System.arraycopy(page, (off + y) * W, f, y * W, W)
        for (y in 0 until HDR) for (x in 0 until W) f[y * W + x] = (60 + (x / 20) % 5).toByte()          // fixed header
        for (y in H - FTR until H) for (x in 0 until W) f[y * W + x] = (if (y > H - FTR + 30 && y < H - FTR + 90 && x in 40..(300 + cursor)) 90 else 20).toByte()   // fixed composer (typing area changes a little)
        return f
    }

    @Test fun findsOffsetWithoutAHint() {
        for (dy in intArrayOf(500, 860, 940)) {
            val m = ShotMatch.find(frame(300), frame(300 + dy), W, H, 0, 2)
            assertEquals("dy=$dy score=${m.score} still=${m.still} mdy=${m.dy} hdr=${m.hdr} ftr=${m.ftr}", ShotMatch.Status.OK, m.status)
            assertEquals(dy, m.dy)
        }
    }

    @Test fun findsOffsetWithAnApproximateHint() {
        val m = ShotMatch.find(frame(1000), frame(1000 + 860), W, H, 862, 2)
        assertEquals(ShotMatch.Status.OK, m.status); assertEquals(860, m.dy)
    }

    @Test fun tolleratesChangingComposer() {
        val m = ShotMatch.find(frame(2000, 0), frame(2000 + 900, 120), W, H, 0, 2)
        assertEquals(ShotMatch.Status.OK, m.status); assertEquals(900, m.dy)
    }

    @Test fun sameScreenIsTheEnd() {
        val m = ShotMatch.find(frame(4000), frame(4000), W, H, 0, 2)
        assertEquals(ShotMatch.Status.END, m.status)
    }

    @Test fun ignoresAToast() {
        val a = frame(3000); val b = frame(3000 + 700)
        val r = Random(5)
        // a toast over the lower middle of the first capture only
        for (y in 1180 until 1260) for (x in 90 until 640) a[y * W + x] = (80 + r.nextInt(120)).toByte()
        val m = ShotMatch.find(a, b, W, H, 0, 2)
        assertEquals("hdr=${m.hdr} ftr=${m.ftr} score=${m.score} still=${m.still} dy=${m.dy}", ShotMatch.Status.OK, m.status); assertEquals(700, m.dy)
    }

    @Test fun splitScreenWithAStaticWindowAbove() {
        val a = frame(5000); val b = frame(5000 + 400)
        val r = Random(9)
        val top = ByteArray(W * 520) { (30 + r.nextInt(200)).toByte() }   // another app's window above, not scrolling
        System.arraycopy(top, 0, a, 0, top.size); System.arraycopy(top, 0, b, 0, top.size)
        val m = ShotMatch.find(a, b, W, H, 0, 2, 90, 0)
        assertEquals("hdr=${m.hdr} ftr=${m.ftr} score=${m.score} still=${m.still} dy=${m.dy}", ShotMatch.Status.OK, m.status); assertEquals(400, m.dy)
    }

    /** WhatsApp style: a doodle wallpaper that stays fixed on the screen while the bubbles scroll over it. */
    private fun withWallpaper(f: ByteArray): ByteArray {
        for (y in HDR until H - FTR) for (x in 0 until W) {
            val i = y * W + x
            if (f[i].toInt() == 0) { val d = ((x / 9 + y / 7) % 5 == 0) || ((x * 3 + y * 5) % 23 == 0); f[i] = (if (d) 70 else 20).toByte() }
        }
        return f
    }
    @Test fun fixedWallpaperUnderScrollingBubbles() {
        val a = withWallpaper(frame(6000)); val b = withWallpaper(frame(6000 + 700))
        val m = ShotMatch.find(a, b, W, H, 0, 2)
        assertEquals("hdr=${m.hdr} ftr=${m.ftr} score=${m.score} still=${m.still} dy=${m.dy}", ShotMatch.Status.OK, m.status); assertEquals(700, m.dy)
    }
    @Test fun fixedWallpaperSameScreenIsTheEnd() {
        val a = withWallpaper(frame(6000)); val b = withWallpaper(frame(6000))
        assertEquals(ShotMatch.Status.END, ShotMatch.find(a, b, W, H, 0, 2).status)
    }

    // My Files style list: near identical rows, a dark static strip on the right edge, translucent-free header. Probe then a big scroll.
    @Test fun repeatedRowsWithStaticRightStrip() {
        val rowH = 190
        fun listFrame(off: Int): ByteArray {
            val f = ByteArray(W * H)
            for (y in 0 until H) for (x in 0 until W) {
                val py = y + off; val r = py / rowH; val iy = py % rowH
                var v = 245
                if (iy == rowH - 1) v = 220
                if (x in 150..520 && iy in 40..70 && (((x / 4) * (r % 5 + 1) + r) % 3 != 0)) v = 40      // title, differs a little per row
                if (x in 150..420 && iy in 100..120 && ((x / 6 + r) % 2 == 0)) v = 150
                if (x in 30..110 && iy in 40..140) v = 70
                f[y * W + x] = v.toByte()
            }
            for (y in 0 until H) for (x in W - 40 until W) f[y * W + x] = 0     // static dark edge panel
            for (y in 0 until 120) for (x in 0 until W) f[y * W + x] = 250.toByte()  // fixed header
            return f
        }
        for (dy in intArrayOf(350, 700, 800)) {
            val m = ShotMatch.find(listFrame(2000), listFrame(2000 + dy), W, H, 0, 2, 0, 0)
            assertEquals("dy=$dy score=${m.score} hdr=${m.hdr}", ShotMatch.Status.OK, m.status)
            assertEquals(dy, m.dy)
        }
    }

    // a video / recorder bubble that changes by itself in the right-edge strip must not break the join or fake motion
    @Test fun ignoresAChangingRightEdgeStrip() {
        val r = Random(9)
        val a = frame(2500); val b = frame(2500 + 700)
        for (y in 300 until 900) for (x in (W * 90 / 100) until W) { a[y * W + x] = r.nextInt(256).toByte(); b[y * W + x] = r.nextInt(256).toByte() }
        val m = ShotMatch.find(a, b, W, H, 0, 2)
        assertEquals(ShotMatch.Status.OK, m.status); assertEquals(700, m.dy)
        val c = frame(2500); val d = frame(2500)
        for (y in 300 until 900) for (x in (W * 90 / 100) until W) { c[y * W + x] = r.nextInt(256).toByte(); d[y * W + x] = r.nextInt(256).toByte() }
        assertEquals(ShotMatch.Status.END, ShotMatch.find(c, d, W, H, 0, 2).status)
    }

    @Test fun findsAFlatHostStripAndIgnoresPlainPages() {
        val f = frame(2500)
        assertNull(ShotMatch.flatBand(f, W, H))
        val g = frame(2500)
        for (y in 1180 until 1191) for (x in 0 until (W * 94 / 100)) g[y * W + x] = 96.toByte()
        val b = ShotMatch.flatBand(g, W, H)
        assertNotNull(b); assertEquals(1180, b!![0]); assertEquals(11, b[1])
        // a white page margin is not a band
        val h2 = ByteArray(W * H) { 255.toByte() }
        assertNull(ShotMatch.flatBand(h2, W, H))
    }

    @Test fun bandTrackerCropsFromTheSecondSightingEvenWhenItBlinks() {
        val t = app.vocr.capture.BandTracker()
        val b = intArrayOf(2194, 11, 96)
        assertEquals(-1, t.see(b))        // first sighting: not cropped yet
        assertEquals(-1, t.see(null))     // the strip is missing in the next capture
        assertEquals(2194, t.see(b))      // seen again at the same row: cropped
        assertEquals(-1, t.see(null))
        assertEquals(2194, t.see(b))      // and every time after
        assertEquals(-1, t.see(intArrayOf(1500, 11, 96)))   // a different row is a new strip (page content moves)
    }

    @Test fun findsABottomBarThatDisappearsWhenScrolling() {
        val a = frame(500); val b = frame(500 + 700)
        val top = H - FTR - 150
        for (y in top until H - FTR) for (x in 0 until W) a[y * W + x] = (if ((x / 40 + y / 6) % 2 == 0) 235 else 20).toByte()   // a nav bar with icons
        val cut = ShotMatch.hiddenFooter(a, b, W, H, 700, HDR, H - FTR)
        assertTrue("cut=$cut", cut in 140..160)
        assertEquals(0, ShotMatch.hiddenFooter(frame(500), frame(500 + 700), W, H, 700, HDR, H - FTR))
    }
}
