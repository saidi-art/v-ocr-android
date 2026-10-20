package app.vocr

import app.vocr.capture.ChromeMap
import app.vocr.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Local only (set VOCR_LAB_CHROME to a folder with shot.rgb, a raw dump of a real stitched long shot, and chrome.txt
 * holding the stitched chrome row ranges, one "start end" per line, from its shot log). Replays the long-shot OCR
 * pipeline with and without the chrome ranges and the token rescue, and prints what the text gains and loses plus
 * the measured cost of the rescue (second-look reads and added milliseconds).
 */
class ChromeOcrLabTest {
    private fun rgb(f: File): Rgb {   // raw dump: big-endian width, height, then w*h RGB bytes (ImageIO is not on the unit-test classpath)
        val ins = java.io.DataInputStream(f.inputStream().buffered())
        val w = ins.readInt(); val h = ins.readInt()
        val raw = ByteArray(w * h * 3); ins.readFully(raw); ins.close()
        val px = IntArray(w * h) { ((raw[it * 3].toInt() and 255) shl 16) or ((raw[it * 3 + 1].toInt() and 255) shl 8) or (raw[it * 3 + 2].toInt() and 255) }
        return Rgb(w, h, px)
    }
    private fun readStrips(eng: OcrEngine, img: Rgb, chrome: List<IntArray>, rescue: Boolean): List<String> {
        val strip = 3200; val ov = 160
        val perStrip = ArrayList<List<Line>>()
        var y0 = 0
        while (y0 < img.h) {
            val y1 = minOf(img.h, y0 + strip)
            val sub = img.crop(0, y0, img.w, y1)
            val found = eng.recognize(sub)
            val kept = if (chrome.isEmpty()) found else found.filter { ln -> !ChromeMap.contains(chrome, y0 + (ln.y0 + ln.y1) / 2) }
            val fixed = if (rescue) kept.map { Tokens.repairLine(eng, sub, it) } else kept
            perStrip.add(fixed.map { Line(Clean.text(it.text), (it.y0 + it.y1) / 2f / sub.h) }.filter { Clean.keep(it.text) })
            if (y1 >= img.h) break
            y0 = y1 - ov
        }
        val sp = Spacing.load(File("src/main/assets/words.txt").readText().split("\n"))
        return Stitch.merge(perStrip.map { st -> st.map { sp.fix(it.text) } }).map { if (rescue) Tokens.fixLine(Fix.lookalikes(it)) else Fix.lookalikes(it) }
    }

    @Test fun chromeAndTokenRescueOnRealCapture() {
        val dir = System.getenv("VOCR_LAB_CHROME")?.let { File(it) } ?: return
        val imgF = File(dir, "shot.rgb"); val chrF = File(dir, "chrome.txt")
        assumeTrue(imgF.exists() && chrF.exists())
        assumeTrue(File("src/main/assets/ocr/det.onnx").exists())
        val chrome = chrF.readLines().filter { it.isNotBlank() }.map { l -> l.trim().split(Regex("\\s+")).let { intArrayOf(it[0].toInt(), it[1].toInt()) } }
        val img = rgb(imgF)
        val eng = OcrEngine(File("src/main/assets/ocr/det.onnx").readBytes(), File("src/main/assets/ocr/rec.onnx").readBytes(), File("src/main/assets/ocr/keys.txt").readText().split("\n"))
        val t0 = System.currentTimeMillis()
        val before = readStrips(eng, img, emptyList(), false)
        val t1 = System.currentTimeMillis()
        eng.reReads = 0
        val after = readStrips(eng, img, chrome, true)
        val t2 = System.currentTimeMillis()
        val lost = before.filter { b -> after.none { a -> Stitch.normKey(a) == Stitch.normKey(b) } }
        val gained = after.filter { a -> before.none { b -> Stitch.normKey(a) == Stitch.normKey(b) } }
        println("CHROMELAB lines before=${before.size} after=${after.size}")
        println("CHROMELAB cost: second-look reads=${eng.reReads}, baseline ${t1 - t0}ms, with-rescue ${t2 - t1}ms")
        println("CHROMELAB lost:"); lost.forEach { println("CHROMELAB   - $it") }
        println("CHROMELAB gained:"); gained.forEach { println("CHROMELAB   + $it") }
        val clock = Regex("^\\d{1,2}:\\d{2}\\b")
        assertTrue("chrome junk still present", after.none { clock.containsMatchIn(it) })
    }
}
