package app.vocr.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import app.vocr.core.Pipeline
import app.vocr.core.StripBuilder
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Screenshot and long (scrolling) screenshot. Every pixel is kept at the device's own resolution: no scaling, no JPEG, PNG only.
 * A long capture is written in parts of at most PART_H rows (viewers blur or refuse very tall images), with an overall cap of MAX_ROWS.
 */
class LongShotSession(private val ctx: Context, first: Bitmap) {
    enum class Outcome { ADDED, END, MISMATCH, CAP }

    private val w = first.width
    private val h = first.height
    private var prev: Bitmap = first
    private var prevLuma: ByteArray = luma(first)
    private var ftrPrev = -1
    private val writer = PartWriter(ctx, w)
    var frames = 1; private set
    val rows: Int get() = writer.rows

    /** Adds the next capture (the page scrolled down by some rows). */
    fun addFrame(cur: Bitmap): Outcome {
        if (cur.width != w || cur.height != h) return Outcome.MISMATCH
        val curLuma = luma(cur)
        val hdr = fixedRows(prevLuma, curLuma, fromTop = true)
        val ftr = fixedRows(prevLuma, curLuma, fromTop = false)
        val top = hdr; val bottom = h - ftr
        if (bottom - top < h / 3) return Outcome.MISMATCH
        // coarse offset on a 360 px wide copy (the app's existing scroll detector), then refined at full resolution
        val s = max(1, w / Pipeline.DET_W)
        val dyS = runCatching {
            val a = Pipeline.toGray(Bitmap.createScaledBitmap(prev, w / s, h / s, true))
            val b = Pipeline.toGray(Bitmap.createScaledBitmap(cur, w / s, h / s, true))
            StripBuilder.detectScroll(a, b)
        }.getOrDefault(0)
        if (dyS <= 0) {
            return if (diff(prevLuma, curLuma, 0, top, bottom) < 3.0) Outcome.END else Outcome.MISMATCH
        }
        var best = Int.MAX_VALUE; var bestScore = Double.MAX_VALUE
        for (d in (dyS * s - s - 1)..(dyS * s + s + 1)) {
            if (d < 1 || d > bottom - top - h / 5) continue
            val sc = diff(prevLuma, curLuma, d, top, bottom)
            if (sc < bestScore) { bestScore = sc; best = d }
        }
        if (best == Int.MAX_VALUE || bestScore > 12.0) return Outcome.MISMATCH
        if (ftrPrev < 0) { writer.add(prev, 0, h - ftr); ftrPrev = ftr }   // first pair: the first capture is written up to its footer
        val start = (h - ftrPrev - best).coerceIn(top, bottom)
        if (bottom > start) writer.add(cur, start, bottom)
        ftrPrev = ftr
        prev.recycle(); prev = cur; prevLuma = curLuma; frames++
        return if (writer.rows >= MAX_ROWS) Outcome.CAP else Outcome.ADDED
    }

    /** Writes the last capture's fixed footer and returns the finished part files (in the app cache). */
    fun finish(): List<File> {
        if (frames == 1) writer.add(prev, 0, h) else writer.add(prev, h - max(ftrPrev, 0), h)
        val f = writer.finish()
        prev.recycle()
        return f
    }

    fun discard() { runCatching { prev.recycle() }; writer.discard() }

    /** Mean absolute grey difference between prev rows (y+dy) and cur rows y over the content band, sampled. */
    private fun diff(a: ByteArray, b: ByteArray, dy: Int, top: Int, bottom: Int): Double {
        var sum = 0L; var n = 0
        var y = top
        while (y < bottom - dy) {
            val ro = y * w; val po = (y + dy) * w
            var x = 0
            while (x < w) { sum += abs((b[ro + x].toInt() and 255) - (a[po + x].toInt() and 255)); n++; x += 5 }
            y += 3
        }
        return if (n == 0) 255.0 else sum.toDouble() / n
    }

    /** Rows at the top (or bottom) that are identical in both captures: a fixed header or footer. Capped at a quarter of the screen. */
    private fun fixedRows(a: ByteArray, b: ByteArray, fromTop: Boolean): Int {
        var n = 0; val cap = h / 4
        while (n < cap) {
            val y = if (fromTop) n else h - 1 - n
            var sum = 0L; var c = 0; var x = 0
            val o = y * w
            while (x < w) { sum += abs((a[o + x].toInt() and 255) - (b[o + x].toInt() and 255)); c++; x += 6 }
            if (sum.toDouble() / c > 1.5) break
            n++
        }
        return n
    }

    companion object {
        const val PART_H = 8000
        const val MAX_ROWS = 40000

        fun luma(bm: Bitmap): ByteArray {
            val w = bm.width; val h = bm.height
            val out = ByteArray(w * h); val row = IntArray(w)
            for (y in 0 until h) {
                bm.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) { val c = row[x]; out[y * w + x] = ((((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000).toByte() }
            }
            return out
        }

        /** True when two captures show the same picture (the page has stopped moving). */
        fun same(a: Bitmap, b: Bitmap): Boolean {
            if (a.width != b.width || a.height != b.height) return false
            var bad = 0; var n = 0
            var y = 0
            while (y < a.height) {
                var x = 0
                while (x < a.width) { val p = a.getPixel(x, y); val q = b.getPixel(x, y); if (abs(((p shr 8) and 255) - ((q shr 8) and 255)) > 6) bad++; n++; x += 13 }
                y += 11
            }
            return bad * 200 < n
        }

        /** True when the picture is (almost) all black: the app blocks screenshots. */
        fun blank(bm: Bitmap): Boolean {
            var sum = 0L; var n = 0; var y = 0
            while (y < bm.height) { var x = 0; while (x < bm.width) { val c = bm.getPixel(x, y); sum += ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255); n++; x += 37 }; y += 41 }
            return n > 0 && sum / (3.0 * n) < 2.0
        }
    }
}

/** Writes rows into PNG parts of at most PART_H rows, in the app cache, so memory stays flat however long the capture is. */
class PartWriter(private val ctx: Context, private val w: Int) {
    private var cur: Bitmap? = null
    private var canvas: Canvas? = null
    private var fill = 0
    val files = ArrayList<File>()
    var rows = 0; private set

    fun add(src: Bitmap, y0: Int, y1: Int) {
        var y = y0
        while (y < y1) {
            if (cur == null) { cur = Bitmap.createBitmap(w, LongShotSession.PART_H, Bitmap.Config.ARGB_8888); canvas = Canvas(cur!!); fill = 0 }
            val n = min(y1 - y, LongShotSession.PART_H - fill)
            canvas!!.drawBitmap(src, Rect(0, y, w, y + n), Rect(0, fill, w, fill + n), null)
            fill += n; y += n; rows += n
            if (fill == LongShotSession.PART_H) flush()
        }
    }

    private fun flush() {
        val b = cur ?: return
        val part = if (fill < b.height) Bitmap.createBitmap(b, 0, 0, w, fill) else b
        val f = File(ctx.cacheDir, "shot_${System.nanoTime()}_${files.size}.png")
        f.outputStream().use { part.compress(Bitmap.CompressFormat.PNG, 100, it) }
        files.add(f)
        if (part !== b) part.recycle()
        b.recycle(); cur = null; canvas = null; fill = 0
    }

    fun finish(): List<File> { if (fill > 0) flush(); return files }
    fun discard() { runCatching { cur?.recycle() }; files.forEach { it.delete() }; files.clear() }
}

/** Saves screenshot parts to Download/ScrollRSI and, when asked, the OCR text of the full-resolution image next to them. */
object ShotSaver {
    /** Returns the saved names. A single part keeps the plain name; several parts are numbered. */
    fun save(ctx: Context, base: String, parts: List<File>, withText: Boolean): String {
        val names = ArrayList<String>()
        parts.forEachIndexed { i, f ->
            val name = if (parts.size == 1) "$base.png" else "$base part ${i + 1}.png"
            val r = app.vocr.core.Saver.saveFile(ctx, name, "image/png", f)
            if (r.uri == null) throw java.io.IOException(r.error)
            names.add(name)
        }
        if (withText) runCatching {
            val text = ocr(ctx, parts)
            if (text.isNotBlank()) app.vocr.core.Saver.saveAs(ctx, base, text)
        }
        parts.forEach { it.delete() }
        return names.joinToString(", ")
    }

    /** OCR on native-resolution strips of about one screen with a small overlap (never on a downscaled copy), stitched like a video job. */
    private fun ocr(ctx: Context, parts: List<File>): String {
        val heights = parts.map { f -> val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeFile(f.path, o); o.outHeight }
        val w = parts.map { f -> val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeFile(f.path, o); o.outWidth }.firstOrNull() ?: return ""
        val total = heights.sum(); val strip = 3200; val ov = 160
        app.vocr.core.Pipeline.gate.lock()
        val eng = app.vocr.core.Reader.load(ctx)
        try {
            val perStrip = ArrayList<List<app.vocr.core.Line>>()
            var y0 = 0
            while (y0 < total) {
                val y1 = min(total, y0 + strip)
                val bm = Bitmap.createBitmap(w, y1 - y0, Bitmap.Config.ARGB_8888); val c = Canvas(bm)
                var base = 0
                for ((i, f) in parts.withIndex()) {
                    val a = max(y0, base); val b = min(y1, base + heights[i])
                    if (b > a) {
                        val dec = android.graphics.BitmapRegionDecoder.newInstance(f.path, false)
                        val reg = dec!!.decodeRegion(Rect(0, a - base, w, b - base), null); dec.recycle()
                        c.drawBitmap(reg, 0f, (a - y0).toFloat(), null); reg.recycle()
                    }
                    base += heights[i]
                }
                val px = IntArray(bm.width * bm.height); bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
                for (k in px.indices) px[k] = px[k] and 0xFFFFFF
                val img = app.vocr.core.Rgb(bm.width, bm.height, px); bm.recycle()
                perStrip.add(eng.recognize(img).map { app.vocr.core.Line(app.vocr.core.Clean.text(it.text), (it.y0 + it.y1) / 2f / img.h) }.filter { app.vocr.core.Clean.keep(it.text) })
                if (y1 >= total) break
                y0 = y1 - ov
            }
            val sp = app.vocr.core.Spacing.load(ctx.assets.open("words.txt").use { String(it.readBytes(), Charsets.UTF_8) }.split("\n"))
            return app.vocr.core.Stitch.merge(perStrip.map { st -> st.map { sp.fix(it.text) } }).map { app.vocr.core.Fix.lookalikes(it) }.joinToString("\n")
        } finally { eng.close(); app.vocr.core.Pipeline.gate.unlock() }
    }
}
