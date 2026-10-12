package app.vocr

import app.vocr.core.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Offline lab: replays a 12 fps raw RGB dump (720x1560) through the real pipeline logic + OCR + stitching. */
class FlingLabTest {
    @Test fun lab() {
        val raw = File("/tmp/fl/f.rgb"); assumeTrue(raw.exists() && File("src/main/assets/ocr/det.onnx").exists())
        val w = 720; val h = 1560; val fsz = w * h * 3
        val n = (raw.length() / fsz).toInt()
        val bytes = raw.readBytes()
        fun frame(i: Int): Rgb { val px = IntArray(w * h) { val o = i * fsz + it * 3; ((bytes[o].toInt() and 255) shl 16) or ((bytes[o + 1].toInt() and 255) shl 8) or (bytes[o + 2].toInt() and 255) }; return Rgb(w, h, px) }
        fun gray(r: Rgb): Gray { val dw = 360; val dh = 780; val d = FloatArray(dw * dh)
            for (y in 0 until dh) for (x in 0 until dw) d[y * dw + x] = (r.gray(x * 2, y * 2) + r.gray(x * 2 + 1, y * 2 + 1)) / 2f
            return Gray(dw, dh, d) }
        val sb = StripBuilder(overlapPx = 18)
        val strips = ArrayList<Rgb>(); var step = 4; var next = 0
        val mode = System.getProperty("labmode") ?: "adaptive"
        var i = 0
        while (i < n) {
            if (i < next) { i++; continue }
            val f = frame(i); val g = gray(f)
            val s32 = Gray(32, 32, FloatArray(1024) { val x = (it % 32) * 360 / 32; val y = (it / 32) * 780 / 32; g.d[y * 360 + x] })
            val dec = sb.push(Hash.phash(s32), g)
            when (dec) {
                is Decision.Full -> strips.add(f)
                is Decision.Crop -> { val sc = h / 780f; val t = (dec.top * sc).toInt().coerceIn(0, h - 1); val b = (dec.bottom * sc).toInt().coerceIn(t + 1, h); strips.add(f.crop(0, t, w, b)) }
                else -> {}
            }
            step = if (sb.lastFailed && mode == "adaptive") 1 else 4
            next = i + step; i++
        }
        val fin = sb.flush()
        println("LAB frames=$n strips=${strips.size} dup=${sb.dup} full=${sb.full} cropped=${sb.cropped} flush=$fin")
        val dir = File("src/main/assets/ocr")
        val eng = OcrEngine(File(dir, "det.onnx").readBytes(), File(dir, "rec.onnx").readBytes(), File(dir, "keys.txt").readText().split("\n"))
        val per = ArrayList<List<Line>>()
        for ((k, s) in strips.withIndex()) {
            val ls = eng.recognize(s).filter { it.conf >= 0.55f && it.text.count { c -> c.isLetterOrDigit() } >= 1 }.map { Line(it.text, (it.y0 + it.y1) / 2f / s.h) }
            println("LAB strip $k ${s.w}x${s.h}: ${ls.size} lines")
            per.add(ls)
        }
        val sp = Spacing.load(File("src/main/assets/words.txt").readText().split("\n"))
        val merged = Stitch.merge(Chrome.strip(per).map { st -> st.map { sp.fix(it.text) } })
        println("LAB raw=${per.sumOf { it.size }} merged=${merged.size} ocrMs det=${eng.detMs} rec=${eng.recMs}")
        merged.forEach { println("LABTXT $it") }
    }
}
