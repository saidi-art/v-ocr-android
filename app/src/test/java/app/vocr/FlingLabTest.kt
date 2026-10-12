package app.vocr

import app.vocr.core.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Offline lab: replays a 12 fps raw RGB dump (720x1560) through the real pipeline logic + OCR + stitching. */
class FlingLabTest {
    @Test fun lab() {
        val raw = File(System.getenv("FLRAW") ?: "/tmp/fl/f.rgb"); assumeTrue(raw.exists() && File("src/main/assets/ocr/det.onnx").exists())
        val w = 720; val h = 1560; val fsz = w * h * 3
        val n = (raw.length() / fsz).toInt()
        val raf = java.io.RandomAccessFile(raw, "r"); val buf = ByteArray(fsz)
        fun frame(i: Int): Rgb { raf.seek(i.toLong() * fsz); raf.readFully(buf); val bytes = buf; val px = IntArray(w * h) { val o = it * 3; ((bytes[o].toInt() and 255) shl 16) or ((bytes[o + 1].toInt() and 255) shl 8) or (bytes[o + 2].toInt() and 255) }; return Rgb(w, h, px) }
        fun gray(r: Rgb): Gray { val dw = 360; val dh = 780; val d = FloatArray(dw * dh)
            for (y in 0 until dh) for (x in 0 until dw) d[y * dw + x] = (r.gray(x * 2, y * 2) + r.gray(x * 2 + 1, y * 2 + 1)) / 2f
            return Gray(dw, dh, d) }
        if (System.getenv("FLDIAG") != null) {
            var pg: Gray? = null
            for (k in 0 until n) { val g = gray(frame(k)); val q = pg
                if (q != null) println("DIAG $k hdr=${StripBuilder.headerRows(q, g)} ftr=${StripBuilder.footerRows(q, g)} dy=${StripBuilder.detectScroll(q, g)} mad=${StripBuilder.meanAbsDiff(q, g)}")
                pg = g }
            return
        }
        val dir = File("src/main/assets/ocr")
        val eng = OcrEngine(File(dir, "det.onnx").readBytes(), File(dir, "rec.onnx").readBytes(), File(dir, "keys.txt").readText().split("\n"))
        val per = ArrayList<List<Line>>()
        fun handle(s: Rgb) {
            val ls = eng.recognize(s).filter { it.conf >= 0.55f && it.text.count { c -> c.isLetterOrDigit() } >= 1 }.map { Line(it.text, (it.y0 + it.y1) / 2f / s.h) }
            println("LAB strip ${per.size} ${s.w}x${s.h}: ${ls.size} lines"); per.add(ls)
        }
        val noocr = System.getenv("FLNOOCR") != null; var nstrips = 0
        val sb = StripBuilder(overlapPx = 18); val rate = RateCtl(3.0)
        val strips = ArrayList<Rgb>(); var step = 4; var next = 0; var lastI = 0
        val mode = System.getProperty("labmode") ?: "adaptive"
        var i = 0
        while (i < n) {
            if (i < next) { i++; continue }
            val f = frame(i); lastI = i; val g = gray(f)
            val s32 = Gray(32, 32, FloatArray(1024) { val x = (it % 32) * 360 / 32; val y = (it / 32) * 780 / 32; g.d[y * 360 + x] })
            val dec = sb.push(Hash.phash(s32), g)
            when (dec) {
                is Decision.Full -> { if (noocr) nstrips++ else handle(f); if (noocr) println("DEC $i full") }
                is Decision.Crop -> { val sc = h / 780f; val t = (dec.top * sc).toInt().coerceIn(0, h - 1); val b = (dec.bottom * sc).toInt().coerceIn(t + 1, h); if (noocr) { nstrips++; println("DEC $i crop $t-$b dy=${sb.lastDy}") } else handle(f.crop(0, t, w, b)) }
                else -> {}
            }
            val srcFps = (System.getenv("FLFPS") ?: "12").toDouble()
            step = if (mode == "adaptive") maxOf(1, Math.round(srcFps / rate.update(sb)).toInt()) else 4
            next = i + step; i++
        }
        val fin = sb.flush()
        println("LAB frames=$n strips=${strips.size} nstrips=$nstrips dup=${sb.dup} full=${sb.full} cropped=${sb.cropped} flush=$fin")
        if (noocr) return
        val flushed = fin
        if (flushed is Decision.Crop) { val sc = h / 780f; val t = (flushed.top * sc).toInt().coerceIn(0, h - 1); val bb = (flushed.bottom * sc).toInt().coerceIn(t + 1, h); handle(frame(lastI).crop(0, t, w, bb)) }
        val sp = Spacing.load(File("src/main/assets/words.txt").readText().split("\n"))
        val merged = Stitch.merge(Chrome.strip(per).map { st -> st.map { sp.fix(it.text) } })
        println("LAB raw=${per.sumOf { it.size }} merged=${merged.size} ocrMs det=${eng.detMs} rec=${eng.recMs}")
        merged.forEach { println("LABTXT $it") }
    }
}
