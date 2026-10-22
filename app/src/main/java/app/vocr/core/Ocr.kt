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
class OcrEngine(detModel: ByteArray, recModel: ByteArray, keys: List<String>, threads: Int = 4, provider: String = "cpu") {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        when (provider) {   // experimental execution providers, used by the Speed test only
            "xnnpack" -> addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            "nnapi" -> addNnapi()
        }
    }
    private val det = env.createSession(detModel, opts)
    private val rec = env.createSession(recModel, opts)
    private val chars: List<String> = listOf("") + keys + listOf(" ")   // 0 = CTC blank, last = space
    var detMs = 0L; var recMs = 0L; var postMs = 0L
    var maxSide = 960
    var recH = 48
    var narrowBoost = 1.0f
    private val NARROW = setOf("i", "l", "I", "j", "t", "f", "r", "1", "|", "!", "'", ".", ",", ":", ";")
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

    /** Adaptive detector resolution: normal text is found at maxSide (960), but small text is crushed by that shrink. When the median line is small, look again at full resolution. */
    var adaptive = true; var smallLineH = 23f; var hiSide = 1600; var hiCount = 0; var lastMedH = 0f
    private fun detect(img: Rgb): List<Box> {
        lastDetSide = maxSide
        val b = detectAt(img, maxSide)
        if (!adaptive || b.size < 3 || max(img.w, img.h) <= maxSide) return b
        // Box height saturates for small text (padding), so use the line pitch: median vertical distance between successive lines.
        val cy = b.map { (it.y0 + it.y1) / 2f }.sorted(); val dy = (1 until cy.size).map { cy[it] - cy[it - 1] }.filter { it > 8f }.sorted()
        if (dy.size < 3) return b
        lastMedH = dy[dy.size / 2]
        if (lastMedH >= smallLineH) return b
        hiCount++
        lastDetSide = max(hiSide, maxSide)
        return detectAt(img, max(hiSide, maxSide))
    }

    private fun detectAt(img: Rgb, side: Int): List<Box> {
        val s = min(1f, side.toFloat() / max(img.w, img.h))
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
        val nw = max(16, ceil(recH.toDouble() * crop.w / crop.h).toInt())
        val im = crop.resize(nw, recH)
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
    private fun wordGaps(crop: Rgb): List<Pair<Float, Float>> {
        val w = crop.w; val h = crop.h
        val g = IntArray(w * h) { crop.gray(it % w, it / w) }
        val bg = g.sortedArray()[g.size / 2]
        val ink = BooleanArray(w)
        for (x in 0 until w) { for (y in 0 until h) if (abs(g[y * w + x] - bg) > 35) { ink[x] = true; break } }
        val minRun = gapFrac * h
        val out = ArrayList<Pair<Float, Float>>(); var st = -1
        for (x in 0 until w) {
            if (!ink[x] && st < 0) st = x
            if (ink[x] && st >= 0) { if (st > 0 && x - st >= minRun) out.add(Pair((st + x) / 2f, (x - st).toFloat())); st = -1 }
        }
        return out
    }

    /** Reads one cropped line box: CTC decode, then word spaces reconstructed from pixel gaps. */
    private fun readCrop(crop: Rgb, x0: Int, y0: Int, x1: Int, y1: Int): OcrLine? {
        val (seq, pos, tc) = readLine(crop)
        if (seq.isEmpty()) return null
        val gaps = wordGaps(crop)
        val d = IntArray(seq.size - 1) { pos[it + 1] - pos[it] }.sortedArray()
        val medGap = if (d.isEmpty()) 1 else d[d.size / 2]
        val sb = StringBuilder(chars[seq[0]])
        for (i in 1 until seq.size) {
            val a = chars[seq[i - 1]]; val c = chars[seq[i]]
            if (a != " " && c != " " && pos[i] - pos[i - 1] >= 0 * medGap) {
                val xa = pos[i - 1] * crop.w.toFloat() / tc.first; val xb = pos[i] * crop.w.toFloat() / tc.first
                val narrow = a in NARROW || c in NARROW
                val need = gapFrac * crop.h * (if (narrow) narrowBoost else 1f)
                if (gaps.any { it.first > xa && it.first < xb && it.second >= need }) sb.append(' ')
            }
            sb.append(c)
        }
        return OcrLine(sb.toString().trim(), x0, y0, x1, y1, tc.second)
    }

    fun recognize(img: Rgb): List<OcrLine> {
        val lines = ArrayList<OcrLine>()
        for (b in detect(img)) {
            val x0 = b.x0.toInt().coerceIn(0, img.w - 1); val y0 = b.y0.toInt().coerceIn(0, img.h - 1)
            val x1 = b.x1.toInt().coerceIn(x0 + 1, img.w); val y1 = b.y1.toInt().coerceIn(y0 + 1, img.h)
            if (y1 - y0 < 14 || x1 - x0 < 18) continue   // specks (wallpaper, icons) are not text lines
            val ln = readCrop(img.crop(x0, y0, x1, y1), x0, y0, x1, y1) ?: continue
            lines.add(ln)
        }
        if (denseRescue && lastDetSide < hiSide && max(img.w, img.h) > maxSide) {
            val t0 = System.nanoTime()
            val (edgeFrac, outsideFrac) = denseSignal(img, lines)
            gateMs += (System.nanoTime() - t0) / 1_000_000
            lastDenseEdge = edgeFrac; lastDenseOutside = outsideFrac
            if (denseGateFires(edgeFrac, outsideFrac, denseEdgeFrac, denseOutsideFrac)) {
                denseFires++
                val fresh = ArrayList<OcrLine>()
                for (b in detectAt(img, max(hiSide, maxSide))) {
                    val cx = (b.x0 + b.x1) / 2f; val cy = (b.y0 + b.y1) / 2f
                    val covered = lines.any { q ->
                        val qx0 = q.x0 - (q.x1 - q.x0) / 4; val qx1 = q.x1 + (q.x1 - q.x0) / 4
                        val qy0 = q.y0 - (q.y1 - q.y0) / 4; val qy1 = q.y1 + (q.y1 - q.y0) / 4
                        cx >= qx0 && cx <= qx1 && cy >= qy0 && cy <= qy1
                    }
                    if (covered) continue
                    val x0 = b.x0.toInt().coerceIn(0, img.w - 1); val y0 = b.y0.toInt().coerceIn(0, img.h - 1)
                    val x1 = b.x1.toInt().coerceIn(x0 + 1, img.w); val y1 = b.y1.toInt().coerceIn(y0 + 1, img.h)
                    if (y1 - y0 < 14 || x1 - x0 < 18) continue   // same speck filter as the first pass
                    val ln = readCrop(img.crop(x0, y0, x1, y1), x0, y0, x1, y1) ?: continue
                    fresh.add(ln)
                }
                if (fresh.isNotEmpty()) {
                    denseAdded += fresh.size
                    lines.addAll(fresh)
                    lines.sortWith(compareBy({ ((it.y0 + it.y1) / 2 / 8).toInt() }, { it.x0 }))   // reading order, same as detect
                }
            }
        }
        return lines
    }


    /**
     * Dense-text rescue ("ink outside boxes" gate). Some pages (squeezed desktop layouts, tiny fonts) crush text
     * below the detector's floor: the first pass then finds only a few scattered big items, so the small-pitch
     * retry above never triggers. Signal: horizontal contrast edges = ink; when half or more of the ink sits
     * OUTSIDE every detected box, the page is under-read and deserves one re-detect at hiSide, reading only the
     * boxes the first pass missed. Clean pages pay only the gate scan (a few ms); nothing is re-read on them.
     */
    var denseRescue = true; var denseEdgeFrac = 0.005f; var denseOutsideFrac = 0.50f
    var denseFires = 0; var denseAdded = 0; var lastDenseEdge = 0f; var lastDenseOutside = 0f; var gateMs = 0L
    private var lastDetSide = 0

    companion object {
        /** (edgeFrac, outsideFrac): how much contrast ink the image holds, and what share of it sits outside the boxes. */
        fun denseSignal(img: Rgb, lines: List<OcrLine>): Pair<Float, Float> {
            val w = img.w; val h = img.h
            if (w.toLong() * h > Int.MAX_VALUE) return Pair(0f, 0f)   // absurd image: no signal, no rescue
            val covered = BooleanArray(w * h)
            for (ln in lines) {
                val x0 = ln.x0.coerceIn(0, w - 1); val x1 = ln.x1.coerceIn(0, w)
                val y0 = ln.y0.coerceIn(0, h - 1); val y1 = ln.y1.coerceIn(0, h)
                for (y in y0 until y1) for (x in x0 until x1) covered[y * w + x] = true
            }
            var edges = 0; var outside = 0
            for (y in 0 until h) {
                var prev = img.gray(0, y)
                for (x in 1 until w) {
                    val g = img.gray(x, y)
                    if (abs(g - prev) > 40) { edges++; if (!covered[y * w + x]) outside++ }
                    prev = g
                }
            }
            return Pair(edges.toFloat() / (w * h), if (edges == 0) 0f else outside.toFloat() / edges)
        }

        fun denseGateFires(edgeFrac: Float, outsideFrac: Float, minEdge: Float = 0.005f, minOutside: Float = 0.50f): Boolean =
            edgeFrac >= minEdge && outsideFrac >= minOutside
    }

    var reReads = 0   // second-look reads done for the token rescue (cost audit)

    /**
     * Second look at one already-detected line, for the token rescue (Tokens.repairLine): the box comes from the first
     * pass (no new detection), the crop is padded and re-rasterised at targetH (the recognizer's input height is fixed
     * at 48, so each raster height feeds it a genuinely different sample). The returned line keeps the first pass's box.
     */
    fun reRead(img: Rgb, ln: OcrLine, targetH: Int = 64): OcrLine? {
        reReads++
        val padY = (ln.y1 - ln.y0) / 3 + 2
        val x0 = (ln.x0 - 8).coerceAtLeast(0); val y0 = (ln.y0 - padY).coerceAtLeast(0)
        val x1 = (ln.x1 + 8).coerceAtMost(img.w); val y1 = (ln.y1 + padY).coerceAtMost(img.h)
        if (x1 - x0 < 16 || y1 - y0 < 14) return null
        var crop = img.crop(x0, y0, x1, y1)
        if (crop.h != targetH) {
            val nw = (crop.w.toLong() * targetH / crop.h).toInt().coerceIn(16, 4096)
            crop = crop.resize(nw, targetH)
        }
        val r = readCrop(crop, 0, 0, crop.w, crop.h) ?: return null
        return OcrLine(r.text, ln.x0, ln.y0, ln.x1, ln.y1, r.conf)
    }
}
