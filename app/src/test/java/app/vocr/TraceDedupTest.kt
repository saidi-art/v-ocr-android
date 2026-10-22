package app.vocr

import app.vocr.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** The traced strip path: proven page movement from the phone replaces the pixel matcher on our own recordings. */
class TraceDedupTest {
    private val W = 360; private val H = 800; private val FOOT = 40
    private val rng = Random(7)
    private val page = FloatArray(W * 6000).also { for (y in 0 until 6000) { val dark = rng.nextInt(3) == 0; for (x in 0 until W) it[y * W + x] = if ((x / 6 + y / 10) % 2 == 0 && dark || rng.nextInt(9) == 0) 30f else 225f } }
    private fun frame(offset: Int): Gray {
        val d = FloatArray(W * H)
        for (y in 0 until H - FOOT) for (x in 0 until W) d[y * W + x] = page[(offset + y) * W + x]
        for (y in H - FOOT until H) for (x in 0 until W) d[y * W + x] = 128f
        return Gray(W, H, d)
    }
    private fun hash(g: Gray): Long {
        val s = FloatArray(32 * 32)
        for (y in 0 until 32) for (x in 0 until 32) {
            var a = 0f; var n = 0
            for (yy in y * H / 32 until (y + 1) * H / 32) for (xx in x * W / 32 until (x + 1) * W / 32) { a += g.d[yy * W + xx]; n++ }
            s[y * 32 + x] = a / n
        }
        return Hash.phash(Gray(32, 32, s))
    }

    @Test fun tracedScrollCoversEveryRowWithFewStrips() {
        val sb = StripBuilder(overlapPx = 18)
        var off = 0; val seen = BooleanArray(6000); var strips = 0
        val step = 20   // small steps: the pixel matcher would drown in whole frames here
        while (off + H <= 5000) {
            val g = frame(off)
            when (val dec = sb.pushTraced(hash(g), g, step)) {
                is Decision.Full -> { strips++; for (y in 0 until H - FOOT) seen[off + y] = true }
                is Decision.Crop -> { strips++; for (y in dec.top until dec.bottom) if (y < H - FOOT) seen[off + y] = true }
                else -> {}
            }
            off += step
        }
        val fin = sb.flush()
        if (fin is Decision.Crop) { strips++; for (y in fin.top until fin.bottom) if (y < H - FOOT) seen[off - step + y] = true }
        val last = off - step + H - FOOT
        for (y in 0 until last) assertTrue("row $y never kept", seen[y])
        // ~4200 scrolled rows at 30% strips of an 800-row frame: about 18 strips, not one per frame (210)
        assertTrue("expected about 18 strips, got $strips", strips in 10..30)
    }

    @Test fun stillPageWithLiveRegionKeepsWholeFrames() {
        val sb = StripBuilder()
        val f0 = frame(0)
        assertTrue(sb.pushTraced(hash(f0), f0, 0) is Decision.Full)   // first frame
        val changed = frame(0); changed.d[400 * W + 10] = 0f; changed.d[400 * W + 11] = 0f
        val d2 = frame(0); for (y in 200 until 260) for (x in 0 until W) d2.d[y * W + x] = 0f   // a new message without scrolling
        assertTrue(sb.pushTraced(hash(d2), d2, 0) is Decision.Full)   // page still, content changed: keep it whole
    }

    @Test fun doubtFallsBackToPixels() {
        val sb = StripBuilder()
        val f0 = frame(0)
        assertTrue(sb.pushTraced(hash(f0), f0, -1) is Decision.Full)
        val f1 = frame(300)
        val d = sb.pushTraced(hash(f1), f1, -1)   // unknown distance: the old pixel matcher decides
        val ref = StripBuilder(); ref.push(hash(f0), f0); val dRef = ref.push(hash(f1), f1)
        assertEquals(dRef.javaClass, d.javaClass)
        assertEquals(ref.scrollMatches, sb.scrollMatches)
    }
}
