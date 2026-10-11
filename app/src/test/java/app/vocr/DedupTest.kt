package app.vocr

import app.vocr.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class DedupTest {
    private val W = 360; private val H = 800; private val FOOT = 40
    private val rng = Random(7)
    // tall page of random "text" rows
    private val page = FloatArray(W * 6000).also { for (y in 0 until 6000) { val dark = rng.nextInt(3) == 0; for (x in 0 until W) it[y * W + x] = if ((x / 6 + y / 10) % 2 == 0 && dark || rng.nextInt(9) == 0) 30f else 225f } }

    private fun frame(offset: Int): Gray {
        val d = FloatArray(W * H)
        for (y in 0 until H - FOOT) for (x in 0 until W) d[y * W + x] = page[(offset + y) * W + x]
        for (y in H - FOOT until H) for (x in 0 until W) d[y * W + x] = 128f   // fixed footer
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

    @Test fun staticFramesAreSkipped() {
        val sb = StripBuilder(); val f = frame(0); val h = hash(f)
        assertTrue(sb.push(h, f) is Decision.Full)
        repeat(5) { assertTrue(sb.push(hash(frame(0)), frame(0)) is Decision.Skip) }
        assertEquals(5, sb.dup)
    }

    @Test fun detectsScrollAndFooter() {
        val dy = StripBuilder.detectScroll(frame(0), frame(120))
        assertEquals(120, dy)
        assertEquals(FOOT, StripBuilder.footerHeight(frame(0), frame(120)))
    }

    @Test fun scrollingCoversAllNewContentWithoutRefeeding() {
        val sb = StripBuilder(overlapPx = 18)
        var off = 0; var covered = 0   // page rows (absolute) emitted so far
        val seen = BooleanArray(6000)
        val step = 100
        while (off + H <= 5000) {
            val g = frame(off); val dec = sb.push(hash(g), g)
            when (dec) {
                is Decision.Full -> for (y in 0 until H - FOOT) seen[off + y] = true
                is Decision.Crop -> for (y in dec.top until dec.bottom) if (y < H - FOOT) seen[off + y] = true
                else -> {}
            }
            off += step
        }
        val fin = sb.flush()
        if (fin is Decision.Crop) for (y in fin.top until fin.bottom) if (y < H - FOOT) seen[off - step + y] = true
        // every page row from 0 to the last visible row must be covered by a kept strip
        val last = off - step + H - FOOT
        val missing = (0 until last).count { !seen[it] }
        assertEquals("uncovered rows", 0, missing)
        // and far fewer strips than frames
        assertTrue("strips=${sb.full + sb.cropped} frames=${sb.total}", sb.full + sb.cropped < sb.total / 2)
    }
}
