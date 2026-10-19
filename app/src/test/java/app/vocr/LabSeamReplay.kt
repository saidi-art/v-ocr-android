package app.vocr

import app.vocr.capture.OverlayTracker
import app.vocr.capture.ShotMatch
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Local only (set VOCR_LAB_DIR to a folder with s1.png and s2.png, two real 1080x2340 chat screenshots). Builds a long real-text page, scrolls it with a fade and a button over the bottom, stitches with the old and the new seam and saves both. */
class LabSeamReplay {
    private val W = 1080; private val H = 2340; private val TOP = 228; private val BOT = 1627; private val FADE = 100

    @Test fun replay() {
        val dir = System.getenv("VOCR_LAB_DIR") ?: return
        val raw = listOf(File(dir, "s1.rgb").readBytes(), File(dir, "s2.rgb").readBytes())
        class Img(val b: ByteArray) { fun getRGB(x: Int, y: Int): Int { val i = (y * W + x) * 3; return ((b[i].toInt() and 255) shl 16) or ((b[i + 1].toInt() and 255) shl 8) or (b[i + 2].toInt() and 255) } }
        val s = raw.map { Img(it) }
        // real page: chunks of real screen rows 240..1480 of the two screenshots, one after the other
        val chunkH = 1240; val chunks = 14
        val pageH = chunkH * chunks + 2 * H
        val page = Array(pageH) { IntArray(W) { 0xFFFFFF } }
        for (c in 0 until chunks) for (y in 0 until chunkH) for (x in 0 until W) page[H + c * chunkH + y][x] = s[c % 2].getRGB(x, 240 + y) and 0xFFFFFF
        val dy = 745
        val offs = (0 until 12).map { H + 100 + it * dy }
        fun frame(off: Int): Array<IntArray> {
            val f = Array(H) { IntArray(W) }
            val ref = s[1]
            for (y in 0 until H) for (x in 0 until W) f[y][x] = if (y in TOP until BOT) page[off + y - TOP][x] else ref.getRGB(x, y) and 0xFFFFFF
            for (i in 0 until FADE) {
                val y = BOT - FADE + i; val a = i * 100 / FADE
                for (x in 0 until W) {
                    val u = f[y][x]
                    val r = ((u shr 16 and 255) * (100 - a) + 255 * a) / 100
                    val g = ((u shr 8 and 255) * (100 - a) + 255 * a) / 100
                    val b = ((u and 255) * (100 - a) + 255 * a) / 100
                    f[y][x] = (r shl 16) or (g shl 8) or b
                }
            }
            val cy = BOT - 55
            for (y in cy - 42..cy + 42) for (x in 540 - 42..540 + 42) if ((x - 540) * (x - 540) + (y - cy) * (y - cy) < 42 * 42) f[y][x] = if ((x - 540) * (x - 540) + (y - cy) * (y - cy) > 36 * 36) 0xDDDDDD else if (Math.abs(x - 540) < 4 && y in cy - 18..cy + 14) 0x202020 else 0xFFFFFF
            return f
        }
        fun luma(f: Array<IntArray>): ByteArray { val b = ByteArray(W * H); for (y in 0 until H) for (x in 0 until W) { val p = f[y][x]; b[y * W + x] = (((p shr 16 and 255) * 30 + (p shr 8 and 255) * 59 + (p and 255) * 11) / 100).toByte() }; return b }
        fun run(fade: Int): List<IntArray> {
            val t = OverlayTracker(W, H, 97, 135)
            val rows = ArrayList<IntArray>()
            fun put(f: Array<IntArray>, a: Int, b: Int) { for (y in a until b) rows.add(f[y]) }
            var prevF = frame(offs[1]); var prevL = luma(prevF)
            t.update(luma(frame(offs[0])), prevL, offs[1] - offs[0])
            var ftrPrev = -1; var base = 0; var stickyF = 0; var stickyT = 0
            for (i in 2 until offs.size) {
                val cf = frame(offs[i]); val cl = luma(cf)
                val m = ShotMatch.find(prevL, cl, W, H, 0, 3, 97, 135)
                assertEquals("pair $i dy=${m.dy} score=${m.score} still=${m.still}", ShotMatch.Status.OK, m.status)
                t.update(prevL, cl, m.dy)
                val ftr = maxOf(m.ftr, stickyF, 713); val top = maxOf(m.hdr, stickyT, 228)   // the real run settled on footer 713 and header 228 (the fade is not detected as a bar there)
                if (m.ftr in 1..(H / 4)) stickyF = maxOf(stickyF, m.ftr); if (m.hdr in 1..(H / 4)) stickyT = maxOf(stickyT, m.hdr)
                val bottom = H - ftr
                if (ftrPrev < 0) { put(prevF, 0, maxOf(0, bottom - fade)); ftrPrev = H - maxOf(0, bottom - fade); base = ftr }
                val sm = ShotMatch.seamFade(H, ftrPrev, ftr, m.dy, top, fade, base)
                if (sm[4] > sm[3]) put(prevF, sm[3], sm[4])
                put(cf, sm[0], sm[1])
                ftrPrev = sm[2]; base = ftr; prevF = cf; prevL = cl
            }
            return rows
        }
        fun save(rows: List<IntArray>, y0: Int, n: Int, name: String) {
            val out = ByteArray(W * n * 3)
            for (y in 0 until n) if (y0 + y < rows.size) for (x in 0 until W) { val p = rows[y0 + y][x]; val i = (y * W + x) * 3; out[i] = (p shr 16).toByte(); out[i + 1] = (p shr 8).toByte(); out[i + 2] = p.toByte() }
            File("/tmp/lab98", name).writeBytes(out)
        }
        File("/tmp/lab98").mkdirs()
        val old = run(0); val new = run(ShotMatch.FADE)
        // ground truth rows (page rows from the first frame's content) to count contaminated rows
        fun bad(rows: List<IntArray>): Int { var b = 0
            val start = offs[1] - TOP
            for (y in rows.indices) { val g = page.getOrNull(start + y) ?: break; if (y >= TOP && !rows[y].contentEquals(g)) b++ }
            return b }
        assertTrue("clean rows expected after the fix: ${bad(new)}", bad(new) == 0); assertTrue(bad(old) > 300)
        println("LAB rows old=${old.size} new=${new.size} bad old=${bad(old)} bad new=${bad(new)}")
        // first image rows are the screen's header rows, so shift the ground truth: compare content only below TOP
        save(old, 1200, 1500, "old.rgb"); save(new, 1200, 1500, "new.rgb")
        File("/tmp/lab98/summary.txt").writeText("old=${old.size} new=${new.size} badOld=${bad(old)} badNew=${bad(new)}\n")
    }
}
