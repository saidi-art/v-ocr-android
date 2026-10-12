package app.vocr.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Plain RGB image (0xRRGGBB ints) so the engine runs on Android and in JVM unit tests. */
class Rgb(val w: Int, val h: Int, val px: IntArray) {
    fun gray(x: Int, y: Int): Int { val c = px[y * w + x]; return (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }

    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Rgb {
        val cw = x1 - x0; val ch = y1 - y0
        val out = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(px, (y0 + y) * w + x0, out, y * cw, cw)
        return Rgb(cw, ch, out)
    }

    /** Bilinear resize. */
    fun resize(nw: Int, nh: Int): Rgb {
        val out = IntArray(nw * nh)
        val sx = w.toFloat() / nw; val sy = h.toFloat() / nh
        for (j in 0 until nh) {
            val fy = ((j + 0.5f) * sy - 0.5f).coerceIn(0f, h - 1f); val y0 = fy.toInt(); val y1 = min(h - 1, y0 + 1); val wy = fy - y0
            for (i in 0 until nw) {
                val fx = ((i + 0.5f) * sx - 0.5f).coerceIn(0f, w - 1f); val x0 = fx.toInt(); val x1 = min(w - 1, x0 + 1); val wx = fx - x0
                val a = px[y0 * w + x0]; val b = px[y0 * w + x1]; val c = px[y1 * w + x0]; val d = px[y1 * w + x1]
                var o = 0
                for (sh in intArrayOf(16, 8, 0)) {
                    val va = (a shr sh) and 255; val vb = (b shr sh) and 255; val vc = (c shr sh) and 255; val vd = (d shr sh) and 255
                    val v = (va * (1 - wx) + vb * wx) * (1 - wy) + (vc * (1 - wx) + vd * wx) * wy
                    o = o or (v.roundToInt().coerceIn(0, 255) shl sh)
                }
                out[j * nw + i] = o
            }
        }
        return Rgb(nw, nh, out)
    }
}

data class OcrLine(val text: String, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val conf: Float)

/**
 * On-device text reader: PP-OCRv4 detector + recognizer (ONNX Runtime), with word-space
 * reconstruction from pixel gaps (the recognizer itself drops most spaces).
 */
class OcrEngine(detModel: ByteArray, recModel: ByteArray, keys: List<String>) {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
    private val det = env.createSession(detModel, opts)
    private val rec = env.createSession(recModel, opts)
    private val chars: List<String> = listOf("") + keys + listOf(" ")   // 0 = CTC blank, last = space
    var detMs = 0L; var recMs = 0L; var postMs = 0L
    var maxSide = 960
    var gapFrac = 0.16f   // a blank pixel run wider than this fraction of the line height is a word gap

    fun close() { det.close(); rec.close(); opts.close() }

    private fun tensor(im: Rgb): OnnxTensor {
        val n = im.w * im.h; val buf = FloatBuffer.allocate(3 * n)
        for (i in 0 until n) {
            val c = im.px[i]
            buf.put(i, (((c shr 16) and 255) / 255f - 0.5f) / 0.5f)
            buf.put(n + i, (((c shr 8) and 255) / 255f - 0.5f) / 0.5f)
            buf.put(2 * n + i, ((c and 255) / 255f - 0.5f) / 0.5f)
        }
        return OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, im.h.toLong(), im.w.toLong()))
    }

    private class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    private fun detect(img: Rgb): List<Box> {
        val s = min(1f, maxSide.toFloat() / max(img.w, img.h))
        val nw = max(32, ((img.w * s) / 32f).roundToInt() * 32); val nh = max(32, ((img.h * s) / 32f).roundToInt() * 32)
        val small = img.resize(nw, nh)
        val t0 = System.nanoTime()
        val prob: FloatArray
        tensor(small).use { inp ->
            det.run(mapOf(det.inputNames.first() to inp)).use { out ->
                val v = out[0].value as Array<Array<Array<FloatArray>>>   // [1][1][h][w]
                prob = FloatArray(nw * nh); for (y in 0 until nh) System.arraycopy(v[0][0][y], 0, prob, y * nw, nw)
            }
        }
        val t1 = System.nanoTime(); detMs += (t1 - t0) / 1_000_000
        // threshold 0.3, 2x2 dilation, 4-connected components
        val m = BooleanArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            val i = y * nw + x
            m[i] = prob[i] > 0.3f || (x > 0 && prob[i - 1] > 0.3f) || (y > 0 && prob[i - nw] > 0.3f) || (x > 0 && y > 0 && prob[i - nw - 1] > 0.3f)
        }
        val seen = BooleanArray(nw * nh); val stack = IntArray(nw * nh)
        val boxes = ArrayList<Box>()
        for (start in m.indices) {
            if (!m[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var minX = nw; var maxX = 0; var minY = nh; var maxY = 0; var area = 0; var sum = 0.0
            while (sp > 0) {
                val i = stack[--sp]; val x = i % nw; val y = i / nw
                area++; sum += prob[i]
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && m[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < nw - 1 && m[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && m[i - nw] && !seen[i - nw]) { seen[i - nw] = true; stack[sp++] = i - nw }
                if (y < nh - 1 && m[i + nw] && !seen[i + nw]) { seen[i + nw] = true; stack[sp++] = i + nw }
            }
            if (area < 20 || sum / area < 0.5) continue
            val bw = (maxX + 1 - minX).toFloat(); val bh = (maxY + 1 - minY).toFloat()
            val d = bw * bh * 1.6f / (2 * (bw + bh))
            boxes.add(Box(max(0f, minX - d) / nw * img.w, max(0f, minY - d) / nh * img.h, min(nw.toFloat(), maxX + 1 + d) / nw * img.w, min(nh.toFloat(), maxY + 1 + d) / nh * img.h))
        }
        postMs += (System.nanoTime() - t1) / 1_000_000
        return boxes.sortedWith(compareBy({ ((it.y0 + it.y1) / 2 / 8).toInt() }, { it.x0 }))
    }

    private fun readLine(crop: Rgb): Triple<List<Int>, List<Int>, Pair<Int, Float>> {
        val nw = max(16, ceil(48.0 * crop.w / crop.h).toInt())
        val im = crop.resize(nw, 48)
        val t0 = System.nanoTime()
        val seq = ArrayList<Int>(); val pos = ArrayList<Int>(); var T = 0; var confSum = 0f
        tensor(im).use { inp ->
            rec.run(mapOf(rec.inputNames.first() to inp)).use { out ->
                val v = (out[0].value as Array<Array<FloatArray>>)[0]   // [T][classes]
                T = v.size
                var prev = 0
                for (t in 0 until T) {
                    var bi = 0; var bv = v[t][0]
                    val row = v[t]
                    for (c in 1 until row.size) if (row[c] > bv) { bv = row[c]; bi = c }
                    if (bi != 0 && bi != prev) { seq.add(bi); pos.add(t); confSum += bv }
                    prev = bi
                }
            }
        }
        recMs += (System.nanoTime() - t0) / 1_000_000
        return Triple(seq, pos, Pair(T, if (seq.isEmpty()) 0f else confSum / seq.size))
    }

    /** x positions (in crop pixels) of blank pixel runs wide enough to be a word gap. */
    private fun wordGaps(crop: Rgb): List<Float> {
        val w = crop.w; val h = crop.h
        val g = IntArray(w * h) { crop.gray(it % w, it / w) }
        val bg = g.sortedArray()[g.size / 2]
        val ink = BooleanArray(w)
        for (x in 0 until w) { for (y in 0 until h) if (abs(g[y * w + x] - bg) > 35) { ink[x] = true; break } }
        val minRun = gapFrac * h
        val out = ArrayList<Float>(); var st = -1
        for (x in 0 until w) {
            if (!ink[x] && st < 0) st = x
            if (ink[x] && st >= 0) { if (st > 0 && x - st >= minRun) out.add((st + x) / 2f); st = -1 }
        }
        return out
    }

    fun recognize(img: Rgb): List<OcrLine> {
        val lines = ArrayList<OcrLine>()
        for (b in detect(img)) {
            val x0 = b.x0.toInt().coerceIn(0, img.w - 1); val y0 = b.y0.toInt().coerceIn(0, img.h - 1)
            val x1 = b.x1.toInt().coerceIn(x0 + 1, img.w); val y1 = b.y1.toInt().coerceIn(y0 + 1, img.h)
            val crop = img.crop(x0, y0, x1, y1)
            val (seq, pos, tc) = readLine(crop)
            if (seq.isEmpty()) continue
            val gaps = wordGaps(crop)
            val sb = StringBuilder(chars[seq[0]])
            for (i in 1 until seq.size) {
                val a = chars[seq[i - 1]]; val c = chars[seq[i]]
                if (a != " " && c != " ") {
                    val xa = pos[i - 1] * crop.w.toFloat() / tc.first; val xb = pos[i] * crop.w.toFloat() / tc.first
                    if (gaps.any { it > xa && it < xb }) sb.append(' ')
                }
                sb.append(c)
            }
            lines.add(OcrLine(sb.toString().trim(), x0, y0, x1, y1, tc.second))
        }
        return lines
    }
}
