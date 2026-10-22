package app.vocr

import app.vocr.core.Gray
import app.vocr.core.ScrollBands
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class ScrollBandsTest {
    private val W = 360; private val H = 800
    private val rng = Random(11)
    private val page = FloatArray(W * 6000).also { for (y in 0 until 6000) { val dark = rng.nextInt(3) == 0; for (x in 0 until W) it[y * W + x] = if ((x / 6 + y / 10) % 2 == 0 && dark || rng.nextInt(9) == 0) 30f else 225f } }
    private val HDR = 60

    // screen = page at offset, with a fixed opaque header band on top
    private fun frame(offset: Int, header: Boolean = true): Gray {
        val d = FloatArray(W * H)
        for (y in 0 until H) for (x in 0 until W) d[y * W + x] = page[(offset + y) * W + x]
        if (header) for (y in 0 until HDR) for (x in 0 until W) d[y * W + x] = if ((x / 12) % 2 == 0) 40f else 220f
        return Gray(W, H, d)
    }

    @Test fun fixedHeaderIsDetectedMovingPageIsNot() {
        val b = ScrollBands(W, H)
        var off = 0
        var step = 118; repeat(8) { b.update(frame(off), frame(off + step), step); off += step; step = 242 - step }
        val top = b.topBand()
        assertTrue("expected about $HDR rows, got $top", top in HDR - 8..HDR + 8)
        assertEquals(0, b.bottomBand())
    }
    @Test fun noFurnitureNoBand() {
        val b = ScrollBands(W, H)
        var off = 0
        var step = 118; repeat(8) { b.update(frame(off, header = false), frame(off + step, header = false), step); off += step; step = 242 - step }
        assertEquals(0, b.topBand())
        assertEquals(0, b.bottomBand())
    }
    @Test fun translucentHeaderNeedsOnlyHalfThePairs() {
        val b = ScrollBands(W, H)
        var off = 0
        var step = 118
        repeat(8) {
            // half the pairs let page content show through the header (blend changes the rows)
            val keepHdr = it % 2 == 0
            b.update(frame(off), frame(off + step, header = keepHdr), step); off += step; step = 242 - step
        }
        val top = b.topBand()
        assertTrue("translucent header should still be found, got $top", top in HDR - 8..HDR + 8)
    }
    @Test fun tooFewPairsProvesNothing() {
        val b = ScrollBands(W, H)
        var off = 0
        var step = 118; repeat(3) { b.update(frame(off), frame(off + step), step); off += step; step = 242 - step }
        assertEquals(0, b.topBand())
    }
}
