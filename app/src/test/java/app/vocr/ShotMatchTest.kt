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
            assertEquals("dy=$dy status", ShotMatch.Status.OK, m.status)
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
}
