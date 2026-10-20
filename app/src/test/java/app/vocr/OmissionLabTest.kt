package app.vocr

import app.vocr.capture.ChromeMap
import app.vocr.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Local only (set VOCR_LAB_OMIT to a folder with shot.rgb - a raw dump of a real stitched capture - and chrome.txt
 * with its system-bar rows). Replays the OCR pipeline on the real shopper capture whose yen and euro signs were
 * dropped, then applies Flags and prints where the honesty markers land. Asserts the flags only ever insert notes:
 * the flagged text itself is byte-identical to the unflagged text.
 */
class OmissionLabTest {
    private fun rgb(f: File): Rgb {
        val ins = java.io.DataInputStream(f.inputStream().buffered())
        val w = ins.readInt(); val h = ins.readInt()
        val raw = ByteArray(w * h * 3); ins.readFully(raw); ins.close()
        val px = IntArray(w * h) { ((raw[it * 3].toInt() and 255) shl 16) or ((raw[it * 3 + 1].toInt() and 255) shl 8) or (raw[it * 3 + 2].toInt() and 255) }
        return Rgb(w, h, px)
    }

    @Test fun omissionFlagsOnRealShopperCapture() {
        val dir = System.getenv("VOCR_LAB_OMIT")?.let { File(it) } ?: return
        val imgF = File(dir, "shot.rgb"); val chrF = File(dir, "chrome.txt")
        assumeTrue(imgF.exists() && chrF.exists())
        assumeTrue(File("src/main/assets/ocr/det.onnx").exists())
        val chrome = chrF.readLines().filter { it.isNotBlank() }.map { l -> l.trim().split(Regex("\\s+")).let { intArrayOf(it[0].toInt(), it[1].toInt()) } }
        val img = rgb(imgF)
        val eng = OcrEngine(File("src/main/assets/ocr/det.onnx").readBytes(), File("src/main/assets/ocr/rec.onnx").readBytes(), File("src/main/assets/ocr/keys.txt").readText().split("\n"))
        val strip = 3200; val ov = 160
        val perStrip = ArrayList<List<Line>>()
        var y0 = 0
        val t0 = System.currentTimeMillis()
        while (y0 < img.h) {
            val y1 = minOf(img.h, y0 + strip)
            val sub = img.crop(0, y0, img.w, y1)
            val found = eng.recognize(sub)
            val kept = found.filter { ln -> !ChromeMap.contains(chrome, y0 + (ln.y0 + ln.y1) / 2) }
            perStrip.add(kept.map { Tokens.repairLine(eng, sub, it) }.map { Line(Clean.text(it.text), (it.y0 + it.y1) / 2f / sub.h) }.filter { Clean.keep(it.text) })
            if (y1 >= img.h) break
            y0 = y1 - ov
        }
        val sp = Spacing.load(File("src/main/assets/words.txt").readText().split("\n"))
        val merged = Stitch.merge(perStrip.map { st -> st.map { sp.fix(it.text) } }).map { Tokens.fixLine(Fix.lookalikes(it)) }
        val t1 = System.currentTimeMillis()
        val readsBeforeFlags = eng.reReads
        val flagged = Flags.apply(merged, singleFrame = false)
        val marks = flagged.filter { it.startsWith("[ScrollRSI:") }
        println("OMITLAB lines merged=${merged.size} flagged=${flagged.size} markers=${marks.size} ocr ${t1 - t0}ms reReads=${eng.reReads}")
        marks.forEachIndexed { i, m ->
            val at = flagged.indexOf(m)
            println("OMITLAB mark ${i + 1}: $m")
            println("OMITLAB   above: ${flagged.getOrNull(at + 1)}")
        }
        // The flags never alter the text itself: stripping the notes gives back the identical read
        // (modulo the approved consecutive-duplicate drop, which removes seam echoes like 'Jun 2026.' here).
        assertEquals(Flags.dedupe(merged), flagged.filter { !it.startsWith("[ScrollRSI:") })
        println("OMITLAB dedupe removed ${merged.size - Flags.dedupe(merged).size} consecutive repeat(s)")
        // The known omissions from this capture must be caught.
        val all = marks.joinToString(" ")
        assertTrue("E200-style letter amount flagged", all.contains("'E200'") || all.contains("'200'"))
        assertTrue("grouped yen amount flagged", all.contains("'30,394'"))
        assertTrue("the flags add no OCR cost (the 9 re-reads are the build-100 token rescue, unchanged)", eng.reReads == readsBeforeFlags)
    }
}
