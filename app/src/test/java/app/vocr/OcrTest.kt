package app.vocr

import app.vocr.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

class OcrTest {
    private fun lcsRatio(a: List<String>, b: List<String>): Double {
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) for (j in 1..b.size) d[i][j] = if (a[i - 1] == b[j - 1]) d[i - 1][j - 1] + 1 else maxOf(d[i - 1][j], d[i][j - 1])
        return d[a.size][b.size].toDouble() / a.size
    }

    @Test fun readsChatStripWithSpaces() {
        val dir = File("src/main/assets/ocr")
        assumeTrue(File(dir, "det.onnx").exists())
        val eng = OcrEngine(File(dir, "det.onnx").readBytes(), File(dir, "rec.onnx").readBytes(), File(dir, "keys.txt").readText().split("\n"))
        val ins = DataInputStream(GZIPInputStream(javaClass.getResourceAsStream("/st0.rgb.gz")!!))
        val iw = ins.readInt(); val ih = ins.readInt()
        val raw = ByteArray(iw * ih * 3); ins.readFully(raw)
        val px = IntArray(iw * ih) { ((raw[it * 3].toInt() and 255) shl 16) or ((raw[it * 3 + 1].toInt() and 255) shl 8) or (raw[it * 3 + 2].toInt() and 255) }
        val t0 = System.currentTimeMillis()
        val lines = eng.recognize(Rgb(iw, ih, px))
        println("OCRTEST ms=${System.currentTimeMillis() - t0} det=${eng.detMs} rec=${eng.recMs} post=${eng.postMs} lines=${lines.size}")
        lines.forEach { println("OCRTEST  ${it.text}") }
        val truth = ("[01] release 4821 14:30 Lyon booking budget invoice [02] Lyon XK29QZ release confirm [03] ref XK29QZ Paris staging please Q3 thanks " +
            "[04] review meeting thanks EUR confirm staging confirm staging [05] release release EUR 14:30 Paris invoice [06] call Lyon ref deploy Q3 deploy booking staging invoice " +
            "[07] meeting release total at release please ref review [08] 1,249.50 release flight AF1380 Q3 deploy release staging thanks confirm [09] tomorrow AF1380 EUR staging ref " +
            "[10] at notes budget call review invoice flight review EUR EUR AF1380 [11] Paris flight 14:30 total notes EUR [12] notes 4821 thanks tomorrow at deploy staging").split(" ")
        val got = lines.joinToString(" ") { it.text }.split(" ").filter { it.isNotEmpty() }
        val r = lcsRatio(truth, got)
        println("OCRTEST word match = $r")
        assertTrue("word match $r", r >= 0.90)
    }

    @Test fun stitchesOverlap() {
        val a = listOf("Hello world this is line one", "second line of text here", "third line of the page", "fourth line almost done")
        val b = listOf("third line of the page", "fourth line almost done", "fifth line brand new", "sixth line also new")
        val m = Stitch.merge(listOf(a, b))
        assertEquals(6, m.size)
        assertEquals("sixth line also new", m.last())
    }

    @Test fun stitchesCutEdgeLine() {
        val a = listOf("alpha beta gamma delta epsilon", "zeta eta theta iota kappa lambda mu", "xi omicron pi rho sigma tau")
        val b = listOf("zeta eta theta iota kappa lambda mu", "xi omicron pi rho", "upsilon phi chi psi omega")  // cut line at the edge
        val m = Stitch.merge(listOf(a, b))
        assertEquals(4, m.size)
        assertEquals("xi omicron pi rho sigma tau", m[2])
    }
}
