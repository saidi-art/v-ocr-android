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
class LongShotSession(private val ctx: Context, first: Bitmap, private val sysTop: Int = 0, private val sysBottom: Int = 0) {
    enum class Outcome { ADDED, END, MISMATCH, CAP }

    private val w = first.width
    private val h = first.height
    private var prev: Bitmap = first
    private var prevLuma: ByteArray = luma(first)
    private var ftrPrev = -1
    private var stickyFtr = 0; private var stickyTop = 0
    private var band: IntArray? = null; private var bandSeen = 0   // a flat strip at the same screen row in successive captures
    private val writer = PartWriter(ctx, w)
    var frames = 1; private set
    /** Fixed rows found at the top and bottom by the last good join (status bar, headers, other windows, composer, navigation bar). */
    var lastHdr = 0; private set
    var lastDy = 0; private set
    var lastFtr = 0; private set
    private val log = StringBuilder()
    fun logText(): String = log.toString()
    fun logLine(t: String) = note(t)
    fun sameAsLast(cur: Bitmap): Boolean = same(prev, cur)
    val rows: Int get() = writer.rows

    /** Adds the next capture (the page scrolled down by some rows). */
    fun addFrame(cur: Bitmap): Outcome {
        if (cur.width != w || cur.height != h) { note("size changed ${cur.width}x${cur.height} vs ${w}x$h"); return Outcome.MISMATCH }
        val curLuma = luma(cur)
        // coarse offset on a 360 px wide copy (the app's existing scroll detector); ShotMatch refines it at full resolution, or finds it itself
        val s = max(1, w / Pipeline.DET_W)
        val dyS = runCatching {
            val a = Pipeline.toGray(Bitmap.createScaledBitmap(prev, w / s, h / s, true))
            val b = Pipeline.toGray(Bitmap.createScaledBitmap(cur, w / s, h / s, true))
            StripBuilder.detectScroll(a, b)
        }.getOrDefault(0)
        val m = ShotMatch.find(prevLuma, curLuma, w, h, if (dyS > 0) dyS * s else 0, s, sysTop, sysBottom)
        note("frame ${frames + 1}: hint=${dyS * s} dy=${m.dy} score=${"%.1f".format(m.score)} still=${"%.1f".format(m.still)} hdr=${m.hdr} ftr=${m.ftr} -> ${m.status}")
        when (m.status) {
            ShotMatch.Status.END -> return Outcome.END
            ShotMatch.Status.MISMATCH -> return Outcome.MISMATCH
            else -> {}
        }
        // A bar that slides away while scrolling and back on a scroll up (X, Chrome) is only 'fixed' in the pairs where it is visible in both captures.
        // In another pair it looks like content and would be written into the middle of the image. Keep the largest bar seen so far, as long as it is a sane size.
        val bnd = ShotMatch.flatBand(curLuma, w, h)
        val b0 = band
        if (bnd != null) {
            if (b0 != null && b0[0] == bnd[0] && b0[1] == bnd[1] && abs(b0[2] - bnd[2]) <= 2) bandSeen++ else { band = bnd; bandSeen = if (frames == 0 && ShotMatch.flatBand(prevLuma, w, h)?.let { it[0] == bnd[0] && it[1] == bnd[1] } == true) 2 else 1 }
        } else { band = null; bandSeen = 0 }
        val bandCrop = if (bandSeen >= 2) h - bnd!![0] else 0
        if (bandCrop > 0) note("flat band at screen rows ${bnd!![0]}..${bnd[0] + bnd[1]} value ${bnd[2]} seen in $bandSeen captures: cropped as a host strip")
        val ftr = max(max(m.ftr, stickyFtr), bandCrop); val top = max(m.hdr, stickyTop); val best = m.dy; val bottom = h - ftr
        if (m.ftr in 1..(h / 4)) stickyFtr = max(stickyFtr, m.ftr)
        if (m.hdr in 1..(h / 4)) stickyTop = max(stickyTop, m.hdr)
        lastHdr = top; lastFtr = ftr; lastDy = best
        if (ftrPrev < 0) { writer.add(prev, 0, h - ftr); ftrPrev = ftr }   // first pair: the first capture is written up to its footer
        val start = (h - ftrPrev - best).coerceIn(top, bottom)
        if (bottom > start) writer.add(cur, start, bottom)
        note("seam: rows $start..$bottom of the new capture appended, image rows now ${writer.rows} (dy=$best hdr=$top ftr=$ftr, detected hdr=${m.hdr} ftr=${m.ftr})")
        ftrPrev = ftr
        prev.recycle(); prev = cur; prevLuma = curLuma; frames++
        return if (writer.rows >= MAX_ROWS) Outcome.CAP else Outcome.ADDED
    }

    private fun note(t: String) { log.append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append("  ").append(t).append("\n"); runCatching { app.vocr.VocrApp.note("longshot: $t", null) } }

    /** Writes the last capture's fixed footer and returns the finished part files (in the app cache). */
    fun finish(): List<File> {
        if (frames == 1) writer.add(prev, 0, h) else writer.add(prev, h - max(ftrPrev, 0), h)
        val f = writer.finish()
        prev.recycle()
        return f
    }

    fun discard() { runCatching { prev.recycle() }; writer.discard() }

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
    class Result(val names: String, val images: List<android.net.Uri>, val textUri: android.net.Uri?, val text: String)

    /** Saves the parts. A single part keeps the plain name; several parts are numbered. */
    fun save(ctx: Context, base: String, parts: List<File>, withText: Boolean): Result {
        val names = ArrayList<String>(); val uris = ArrayList<android.net.Uri>()
        parts.forEachIndexed { i, f ->
            val name = if (parts.size == 1) "$base.png" else "$base part ${i + 1}.png"
            val r = app.vocr.core.Saver.saveFile(ctx, name, "image/png", f)
            if (r.uri == null) throw java.io.IOException(r.error)
            names.add(name); uris.add(r.uri)
        }
        var tu: android.net.Uri? = null; var tt = ""
        if (withText) runCatching {
            val text = ocr(ctx, parts)
            if (text.isNotBlank()) { tu = app.vocr.core.Saver.saveAs(ctx, base, text).uri; tt = text }
        }
        parts.forEach { it.delete() }
        return Result(names.joinToString(", "), uris, tu, tt)
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


/** Pure offset finder for two captures (grey bytes, w x h). Kept free of Android classes so it can be unit-tested. */
object ShotMatch {
    /** Columns right of this percentage of the width are not scored: the screen-recorder bubble, a picture-in-picture video or an edge panel often sit there and change by themselves. */
    const val EDGE = 88
    enum class Status { OK, END, MISMATCH }

    /** A flat band: 6 to 20 rows in the lower 40% of the screen, one single grey value across the left 90% of the width, clearly different from the rows above and below.
     *  Returns (first row, row count, value) or null. Used to find a host or system strip that sits at the same screen row in every capture. */
    fun flatBand(a: ByteArray, w: Int, h: Int): IntArray? {
        val xe = w * 90 / 100
        fun rowVal(y: Int): Int {   // the row's value when flat, else -1
            val o = y * w; val v = a[o].toInt() and 255
            var x = 0
            while (x < xe) { if (abs((a[o + x].toInt() and 255) - v) > 2) return -1; x += 3 }
            return v
        }
        var y = h * 60 / 100
        while (y < h) {
            val v = rowVal(y)
            if (v in 30..200) {
                var e = y + 1
                while (e < h && rowVal(e).let { it >= 0 && abs(it - v) <= 2 }) e++
                val n = e - y
                if (n in 6..20 && y > 0) {
                    val above = rowVal(y - 1); val below = if (e < h) rowVal(e) else -1
                    if ((above < 0 || abs(above - v) > 20) && (below < 0 || abs(below - v) > 20)) return intArrayOf(y, n, v)
                }
                y = e
            } else y++
        }
        return null
    }
    class Match(val status: Status, val dy: Int, val score: Double, val still: Double, val hdr: Int, val ftr: Int)

    /** Rows at the top (or bottom) that are identical in both captures: a fixed header or footer. Capped at 45% of the screen (a split-screen window above or below is fixed too). */
    fun fixedRows(a: ByteArray, b: ByteArray, w: Int, h: Int, fromTop: Boolean): Int {
        // A fixed bar is the run of rows from the edge that stay the same between the two captures. A blinking cursor, a typing
        // indicator or a clock changes a few rows inside it, so only a run of RUN differing rows in a row ends the bar.
        val cap = h * 45 / 100; val run = 24
        var last = 0; var miss = 0; var n = 0
        while (n < cap) {
            val y = if (fromTop) n else h - 1 - n
            var sum = 0L; var c = 0; var x = 0
            val o = y * w
            while (x < w * EDGE / 100) { sum += abs((a[o + x].toInt() and 255) - (b[o + x].toInt() and 255)); c++; x += 6 }
            if (sum.toDouble() / c > 1.5) { miss++; if (miss >= run) break } else { miss = 0; last = n + 1 }
            n++
        }
        return last
    }

    /**
     * Mean absolute difference between prev rows (y+dy) and cur rows y over the content band, counted only where there is something to
     * compare (an edge in either picture). Flat areas and a fixed wallpaper (same pixel in both captures) are left out; flat dark areas match at every offset and would otherwise make any offset look good.
     * Returns 255 when there is too little structure to judge.
     */
    fun diff(a: ByteArray, b: ByteArray, w: Int, dy: Int, top: Int, bottom: Int, rowStep: Int = 3, colStep: Int = 5, movingOnly: Boolean = true, minSamples: Int = 60): Double {
        // per-row mean over edge samples; the worst quarter of rows (a toast, a heads-up notification or a cursor can cover that much) is ignored (a typing cursor, a changing composer, a clock)
        val rows = ArrayList<Double>(); var n = 0
        var y = top
        while (y < bottom - dy) {
            val ro = y * w; val po = (y + dy) * w
            var rs = 0L; var rn = 0
            var x = 0
            while (x + 5 < w * EDGE / 100) {
                val c0 = b[ro + x].toInt() and 255; val p0 = a[po + x].toInt() and 255
                val edge = abs(c0 - (b[ro + x + 5].toInt() and 255)) > 18 || abs(p0 - (a[po + x + 5].toInt() and 255)) > 18
                // movingOnly: a pixel that is the same in both captures at the same screen position is background (a fixed wallpaper, a fixed bar), not page content
                if (edge && (!movingOnly || abs(c0 - (a[ro + x].toInt() and 255)) > 12)) { rs += abs(c0 - p0); rn++ }
                x += colStep
            }
            if (rn > 0) { rows.add(rs.toDouble() / rn); n += rn }
            y += rowStep
        }
        if (n < minSamples || rows.size < 6) { lastTrim = 255.0; return 255.0 }
        val all = rows.average()
        rows.sort()
        val keep = (rows.size * 3) / 4
        var t = 0.0
        for (i in 0 until keep) t += rows[i]
        lastTrim = t / keep
        return lastTrim + 0.3 * all   // ranks offsets; the trimmed part alone decides acceptance
    }
    private var lastTrim = 255.0

    fun find(a: ByteArray, b: ByteArray, w: Int, h: Int, hint: Int, scale: Int, minTop: Int = 0, minBottom: Int = 0): Match {
        val hdr = max(minTop, fixedRows(a, b, w, h, true)); val ftr = max(minBottom, fixedRows(a, b, w, h, false))
        val top = hdr; val bottom = h - ftr
        if (bottom - top < h / 3) {
            // nearly everything is fixed: either the page stopped moving (the end) or there is no room to match
            var sum = 0L; var n = 0; var y = 0
            while (y < h) { var x = 0; while (x < w * EDGE / 100) { sum += abs((a[y * w + x].toInt() and 255) - (b[y * w + x].toInt() and 255)); n++; x += 7 }; y += 5 }
            return if (sum.toDouble() / n < 1.0) Match(Status.END, 0, 0.0, 0.0, hdr, ftr) else Match(Status.MISMATCH, 0, 255.0, 255.0, hdr, ftr)
        }
        val maxDy = bottom - top - h / 8
        val still = diff(a, b, w, 0, top, bottom, movingOnly = false)
        var best = -1; var bestScore = Double.MAX_VALUE; var bestTrim = 255.0
        fun consider(d: Int) { if (d < 1 || d > maxDy) return; val sc = diff(a, b, w, d, top, bottom); if (sc < bestScore) { bestScore = sc; best = d; bestTrim = lastTrim } }
        if (hint > 0) for (d in (hint - scale - 1)..(hint + scale + 1)) consider(d)
        if (best < 0 || bestTrim > 12.0) {
            // the coarse detector gave nothing usable (dark chats, sparse pages): scan every offset, coarse then fine
            val cands = ArrayList<Pair<Int, Double>>()
            var d = 6
            while (d <= maxDy) { cands.add(d to diff(a, b, w, d, top, bottom, 5, 7, true, 25)); d += 4 }
            cands.sortBy { it.second }
            for ((d0, _) in cands.take(4)) for (e in (d0 - 5)..(d0 + 5)) consider(e)
        }
        if (best > 0 && bestTrim <= 12.0) {   // nearly equal neighbours (flat shapes barely change with a one pixel shift): decide with a denser look
            val c0 = best
            for (d in (c0 - 2)..(c0 + 2)) {
                if (d < 1 || d > maxDy || d == c0) continue
                val sc = diff(a, b, w, d, top, bottom, 1, 2)
                val sb = diff(a, b, w, c0, top, bottom, 1, 2)
                if (sc < sb) { best = d; bestScore = sc; bestTrim = lastTrim }
            }
        }
        if (best < 0 || bestTrim > 12.0 || bestScore > 255.0 - 1) {
            return if (still < 3.0) Match(Status.END, 0, bestScore, still, hdr, ftr) else Match(Status.MISMATCH, 0, bestScore, still, hdr, ftr)
        }
        if (still < 255.0 && bestScore > still * 0.8 && still < 3.0) return Match(Status.END, 0, bestScore, still, hdr, ftr)   // no motion: the end of the page
        return Match(Status.OK, best, bestScore, still, hdr, ftr)
    }
}
