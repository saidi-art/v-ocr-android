package app.vocr

import app.vocr.capture.SeamTrust
import app.vocr.capture.ShotMatch
import app.vocr.core.Pipeline
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Local only (set VOCR_LAB_SEAM to a folder with prev.rgb and cur.rgb, raw dumps of two consecutive captures of a page
 * that changed between them). Measures what the seam matcher sees in that pair and what the position-only rule decides,
 * and writes seam.rgb: the two frames joined at the accepted offset, for a visual check of the join.
 */
class SeamLabTest {
    private class Raw(val w: Int, val h: Int, val px: ByteArray)   // rgb bytes
    private fun raw(f: File): Raw {
        val ins = java.io.DataInputStream(f.inputStream().buffered())
        val w = ins.readInt(); val h = ins.readInt()
        val b = ByteArray(w * h * 3); ins.readFully(b); ins.close()
        return Raw(w, h, b)
    }
    private fun luma(r: Raw): ByteArray {
        val out = ByteArray(r.w * r.h)
        for (i in 0 until r.w * r.h) out[i] = (((r.px[i * 3].toInt() and 255) * 299 + ((r.px[i * 3 + 1].toInt() and 255) * 587) + (r.px[i * 3 + 2].toInt() and 255) * 114) / 1000).toByte()
        return out
    }

    @Test fun positionOnlySeamOnRealFrames() {
        val dir = System.getenv("VOCR_LAB_SEAM")?.let { File(it) } ?: return
        val pf = File(dir, "prev.rgb"); val cf = File(dir, "cur.rgb")
        assumeTrue(pf.exists() && cf.exists())
        val p = raw(pf); val c = raw(cf)
        assumeTrue(p.w == c.w && p.h == c.h)
        val w = p.w; val h = p.h
        val prev = luma(p); val cur = luma(c)
        val s = maxOf(1, w / Pipeline.DET_W)
        val m = ShotMatch.find(prev, cur, w, h, 0, s, 97, 135)
        println("SEAMLAB find: dy=${m.dy} score=${"%.1f".format(m.score)} still=${"%.1f".format(m.still)} hdr=${m.hdr} ftr=${m.ftr} -> ${m.status}")
        val med = SeamTrust.positionOnlyDy(listOf(m.dy, m.dy, m.dy), 1.00f)
        println("SEAMLAB positionOnlyDy(3 recaptures at dy=${m.dy}, ratio 1.00) = $med")
        val rawHdr = ShotMatch.fixedRows(prev, cur, w, h, true); val rawFtr = ShotMatch.fixedRows(prev, cur, w, h, false)
        println("SEAMLAB fixedRows on the changed pair (not trusted for a position-only seam): hdr=$rawHdr ftr=$rawFtr")
        if (med <= 0) { println("SEAMLAB no position-only seam for this pair"); return }
        // first-pair seam math, as the fixed forceSeamAt does it: system bars only, no pair-measured bands
        val hdr = 97; val ftr = 135
        val top = maxOf(hdr, 0); val bottom = h - ftr
        val prevEnd = maxOf(0, bottom - ShotMatch.FADE)
        val ftrPrev = h - prevEnd
        val sm = ShotMatch.seamFade(h, ftrPrev, ftr, med, top, ShotMatch.FADE, ftr)
        val start = sm[0]; val bottomW = sm[1]
        println("SEAMLAB seam: prev rows 0..$prevEnd + cur rows $start..$bottomW (fade credit ${sm[2]}, kept-prev ${sm[3]}..${sm[4]})")
        val outH = prevEnd + maxOf(0, sm[4] - sm[3]) + maxOf(0, bottomW - start)
        val out = java.io.DataOutputStream(File(dir, "seam.rgb").outputStream().buffered())
        out.writeInt(w); out.writeInt(outH)
        fun put(r: Raw, a: Int, b: Int) { out.write(r.px, a * w * 3, (b - a) * w * 3) }
        put(p, 0, prevEnd); if (sm[4] > sm[3]) put(p, sm[3], sm[4]); if (bottomW > start) put(c, start, bottomW)
        out.close()
        println("SEAMLAB wrote seam.rgb ${w}x$outH")
    }
}
