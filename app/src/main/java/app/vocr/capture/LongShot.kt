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

/** Row ranges of the stitched image that are screen chrome (a fixed header or footer band), left in the picture but out of the OCR text. Pure, unit-tested. */
object ChromeMap {
    /** Records the parts of frame rows [y0, y1) that fall inside the header band [0, top) or the footer band [h - ftr, h), shifted to stitched rows starting at sy. */
    fun add(out: MutableList<IntArray>, sy: Int, y0: Int, y1: Int, top: Int, ftr: Int, h: Int) {
        if (y1 <= y0) return
        if (top > 0 && y0 < top) out.add(intArrayOf(sy, sy + min(y1, top) - y0))
        val fb = h - ftr
        if (ftr > 0 && y1 > fb) out.add(intArrayOf(sy + max(y0, fb) - y0, sy + y1 - y0))
    }
    fun contains(ranges: List<IntArray>, y: Int): Boolean { for (r in ranges) if (y >= r[0] && y < r[1]) return true; return false }

    /** The status bar and navigation bar bands of one full-screen capture: the only chrome a lone frame proves (app bars need a moving pair). */
    fun systemBars(h: Int, sysTop: Int, sysBottom: Int): List<IntArray> {
        val out = ArrayList<IntArray>(2); val cap = h / 4
        if (sysTop in 1..cap) out.add(intArrayOf(0, sysTop))
        if (sysBottom in 1..cap) out.add(intArrayOf(h - sysBottom, h))
        return out
    }
}

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
    private var ftrBasePrev = 0
    private var stickyFtr = 0; private var stickyTop = 0
    private val bands = BandTracker(); private var bandSeeded = false   // a flat strip at the same screen row, seen in at least two captures of the run (it may be missing from some)
    private val maxRows = runCatching { ctx.getSharedPreferences("vocr", Context.MODE_PRIVATE).getInt("max_rows", MAX_ROWS) }.getOrDefault(MAX_ROWS).coerceIn(PART_H, 400000)
    private val overlay = OverlayTracker(w, h, sysTop, sysBottom)
    private val writer = PartWriter(ctx, w)
    var frames = 1; private set
    // Opt-in debug option (settings, off by default): every capture of the run is also saved as a PNG named "ScrollRSI debug <time> NN.png" so a problem can be analysed. These files contain your screen content.
    private val debugOn = runCatching { ctx.getSharedPreferences("vocr", Context.MODE_PRIVATE).getBoolean("debug_frames", false) }.getOrDefault(false)
    private val debugStamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.US).format(java.util.Date())
    private var debugN = 0
    private fun dump(bm: Bitmap) {
        if (!debugOn) return
        val copy = runCatching { bm.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        val n = ++debugN
        Thread {
            runCatching {
                val f = File(ctx.cacheDir, "debug_${System.nanoTime()}.png")
                f.outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
                app.vocr.core.Saver.saveFile(ctx, "ScrollRSI debug $debugStamp ${"%02d".format(n)}.png", "image/png", f)
                f.delete()
            }
            copy.recycle()
        }.start()
    }
    init { dump(first) }
    /** Fixed rows found at the top and bottom by the last good join (status bar, headers, other windows, composer, navigation bar). */
    var lastHdr = 0; private set
    var lastDy = 0; private set
    var lastFtr = 0; private set
    /** The scroll offset measured for the most recent addFrame attempt, also when it failed to match (-1 = none). */
    var lastTryDy = -1; private set
    private val chrome = ArrayList<IntArray>()
    private val marks = ArrayList<Mark>()
    /** Text notes for the OCR output (gap at a re-anchor, mixed versions at a position-only seam), in stitched rows. */
    fun marks(): List<Mark> = marks
    /** Stitched row ranges kept in the image but left out of the OCR text: the first capture's header band and the last capture's footer band (the bands the shot log records per pair). */
    fun chromeRanges(): List<IntArray> = chrome
    /** One capture, no pair seen: only the system bars are provable chrome (status bar top, navigation bar bottom). */
    fun systemChrome(): List<IntArray> = ChromeMap.systemBars(h, sysTop, sysBottom)
    private fun addRows(src: Bitmap, y0: Int, y1: Int, top: Int, ftr: Int) { ChromeMap.add(chrome, writer.rows, y0, y1, top, ftr, h); writer.add(src, y0, y1) }
    private val log = StringBuilder()
    fun logText(): String = log.toString()
    fun logLine(t: String) = note(t)
    fun sameAsLast(cur: Bitmap): Boolean = same(prev, cur)
    var gaps = 0; private set
    /** The page could not be lined up with the last accepted capture. Close the current image part and continue from this capture; a section between the two may be missing (told to the user at the end). */
    fun reanchor(cur: Bitmap, estMissing: Int = -1): Boolean {
        if (cur.width != w || cur.height != h) return false
        writer.breakPart()
        if (estMissing > 0) marks.add(Mark(writer.rows, "[ScrollRSI: possible gap - up to ~$estMissing px of content may be missing here]"))
        val old = prev
        prev = cur; prevLuma = luma(cur); ftrPrev = -1; frames++; gaps++
        runCatching { old.recycle() }
        note("re-anchored on the current capture (no match with the last one): a section may be missing here, new image part. gaps=$gaps")
        return true
    }
    val rows: Int get() = writer.rows

    /**
     * Adds the next capture (the page scrolled down by some rows). phoneDy is the distance the phone itself measured
     * for the swipe (accessibility scroll events; -1 = no usable reading). It outranks pixel guessing: on uniform chat
     * text the pixel matcher fails about half the time while the phone's number stays exact.
     */
    fun addFrame(cur: Bitmap, phoneDy: Int = -1): Outcome {
        lastTryDy = -1
        if (cur.width != w || cur.height != h) { note("size changed ${cur.width}x${cur.height} vs ${w}x$h"); return Outcome.MISMATCH }
        // The phone says the page barely moved after a full drag: this is the end, whatever pixels make of it.
        if (phoneDy in 0..11) { note("frame ${frames + 1}: phone measured only ${phoneDy}px of movement: the page has ended"); return Outcome.END }
        dump(cur)
        val curLuma = luma(cur)
        val s = max(1, w / Pipeline.DET_W)
        val m: ShotMatch.Match
        var hintUsed = 0
        if (phoneDy > 0) {
            // search around the phone's distance (agreement is within a few percent when pixels work at all)
            m = ShotMatch.find(prevLuma, curLuma, w, h, phoneDy, max(s, phoneDy / 12 + 6), max(sysTop, overlay.header()), max(sysBottom, overlay.footer()))
            hintUsed = phoneDy
        } else {
            // coarse offset on a 360 px wide copy (the app's existing scroll detector); ShotMatch refines it at full resolution, or finds it itself
            val dyS = runCatching {
                val a = Pipeline.toGray(Bitmap.createScaledBitmap(prev, w / s, h / s, true))
                val b = Pipeline.toGray(Bitmap.createScaledBitmap(cur, w / s, h / s, true))
                StripBuilder.detectScroll(a, b)
            }.getOrDefault(0)
            m = ShotMatch.find(prevLuma, curLuma, w, h, if (dyS > 0) dyS * s else 0, s, max(sysTop, overlay.header()), max(sysBottom, overlay.footer()))
            hintUsed = if (dyS > 0) dyS * s else 0
        }
        lastTryDy = m.dy
        note("frame ${frames + 1}: hint=$hintUsed dy=${m.dy} score=${"%.1f".format(m.score)} still=${"%.1f".format(m.still)} hdr=${m.hdr} ftr=${m.ftr} phone=$phoneDy -> ${m.status}")
        val agree = m.status == ShotMatch.Status.OK && phoneDy > 0 && abs(m.dy - phoneDy) <= max(24, phoneDy / 10)
        if (m.status == ShotMatch.Status.OK && (phoneDy <= 0 || agree))
            return applySeam(cur, curLuma, m.dy, m.hdr, m.ftr, m.score >= m.still * 0.9, false)
        if (phoneDy >= 40) {
            // Pixels cannot prove this seam (uniform text, a live page) but the phone measured the movement: seam at its distance.
            // Below 40 px the phone's nudge may be real yet tiny; there pixels decide.
            if (m.status != ShotMatch.Status.OK) note("frame ${frames + 1}: pixels cannot prove this seam (${m.status} dy=${m.dy}); the phone measured ${phoneDy}px: seam placed by the phone")
            else note("frame ${frames + 1}: pixels found dy=${m.dy} but the phone measured ${phoneDy}px (beyond tolerance): seam placed by the phone")
            lastTryDy = phoneDy
            return applySeam(cur, curLuma, phoneDy, m.hdr, m.ftr, false, false)
        }
        return when (m.status) {
            ShotMatch.Status.END -> Outcome.END
            ShotMatch.Status.MISMATCH -> Outcome.MISMATCH
            else -> Outcome.MISMATCH
        }
    }

    /**
     * The pixel match cannot prove this seam (a live page changed while scrolling), but the retry captures kept agreeing on the offset, so the seam is
     * accepted on position only and the join is marked in the text ("may mix versions"). Same bands and seam math as a proven match, without the hidden-footer cut.
     */
    fun forceSeamAt(cur: Bitmap, dy: Int): Outcome {
        if (cur.width != w || cur.height != h) { note("size changed ${cur.width}x${cur.height} vs ${w}x$h"); return Outcome.MISMATCH }
        if (dy <= 0 || dy >= h) return Outcome.MISMATCH
        dump(cur)
        val curLuma = luma(cur)
        // This pair's own fixedRows is not evidence here: a static region fakes a footer up to the 45% cap (would cut real content), a changed bar fakes
        // none. Only the offset is trusted, plus the system bars and the bands the run already accumulated from proven pairs.
        note("frame ${frames + 1}: seam accepted on position only: dy=$dy (system bars hdr=$sysTop ftr=$sysBottom), seam-trust=position-only")
        return applySeam(cur, curLuma, dy, sysTop, sysBottom, false, true)
    }

    private fun applySeam(cur: Bitmap, curLuma: ByteArray, dy: Int, mHdr: Int, mFtr: Int, matchTrusted: Boolean, positionOnly: Boolean): Outcome {
        // A bar that slides away while scrolling and back on a scroll up (X, Chrome) is only 'fixed' in the pairs where it is visible in both captures.
        // In another pair it looks like content and would be written into the middle of the image. Keep the largest bar seen so far, as long as it is a sane size.
        if (!bandSeeded) { bandSeeded = true; bands.see(ShotMatch.flatBand(prevLuma, w, h)) }   // the first capture counts as a sighting too
        val bnd = ShotMatch.flatBand(curLuma, w, h)
        val bRow = bands.see(bnd)
        val bandCrop = if (bRow >= 0) h - bRow else 0
        if (bandCrop > 0) note("flat band at screen rows ${bnd!![0]}..${bnd[0] + bnd[1]} value ${bnd[2]} (sighting ${bands.count(bnd)}): cropped as a host strip")
        else if (bnd != null) note("flat band at screen rows ${bnd[0]}..${bnd[0] + bnd[1]} value ${bnd[2]}: first sighting, not cropped yet")
        // Every accepted seam has a trusted dy (a match, the phone's measurement, or recaptures that kept agreeing), so every
        // seam feeds the overlay evidence: a composer bar is learned even when pixels never prove a single pair (build 104 baked it in).
        if (overlay.update(prevLuma, curLuma, dy)) {
            val oF = overlay.footer(); val oH = overlay.header()
            if (oF > 0 || oH > 0) note("overlay bar (stays at the same screen rows while the page moves): footer $oF rows, header $oH rows (evidence from ${overlay.pairs} pair(s))")
        }
        val ovF = overlay.footer(); val ovH = overlay.header()
        val hid = if (ShotMatch.HIDDEN_FOOTER) ShotMatch.hiddenFooter(prevLuma, curLuma, w, h, dy, mHdr, h - mFtr, !matchTrusted) else 0
        val hidCrop = if (hid in 1..(h / 4)) mFtr + hid else 0
        if (hidCrop > 0) note("a bar at the bottom of the previous capture is gone in this one: cutting $hid rows above the footer")
        val ftr = max(max(max(max(mFtr, stickyFtr), bandCrop), hidCrop), ovF); val top = max(max(mHdr, stickyTop), ovH); val best = dy; val bottom = h - ftr
        if (max(mFtr, hidCrop) in 1..(h / 4)) stickyFtr = max(stickyFtr, max(mFtr, hidCrop))
        if (mHdr in 1..(h / 4)) stickyTop = max(stickyTop, mHdr)
        lastHdr = top; lastFtr = ftr; lastDy = best
        // The last FADE rows above the footer often carry a fade and a "jump to latest" button that are not page content. They are not written now: the next capture supplies the same rows clean.
        if (ftrPrev < 0) { addRows(prev, 0, max(0, bottom - ShotMatch.FADE), top, ftr); ftrPrev = h - max(0, bottom - ShotMatch.FADE); ftrBasePrev = ftr }   // first pair: the new capture's own header band is chrome
        val sm = ShotMatch.seamFade(h, ftrPrev, ftr, best, top, ShotMatch.FADE, ftrBasePrev)
        val start = sm[0]; val bottomW = sm[1]
        if (sm[4] > sm[3]) { addRows(prev, sm[3], sm[4], 0, 0); note("rows ${sm[3]}..${sm[4]} of the previous capture kept (the new one does not reach back that far)") }
        if (positionOnly) marks.add(Mark(writer.rows, "[ScrollRSI: page changed during capture - text around this line may mix versions]"))
        if (bottomW > start) addRows(cur, start, bottomW, top, ftr)
        note("seam: rows $start..$bottomW of the new capture appended, image rows now ${writer.rows} (dy=$best hdr=$top ftr=$ftr, detected hdr=$mHdr ftr=$mFtr, last ${ShotMatch.FADE} rows left for the next capture)")
        ftrPrev = sm[2]   // where the written rows end, in this capture's coordinates, counted from its bottom
        ftrBasePrev = ftr
        prev.recycle(); prev = cur; prevLuma = curLuma; frames++
        return if (writer.rows >= maxRows) Outcome.CAP else Outcome.ADDED
    }

    private fun note(t: String) { log.append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append("  ").append(t).append("\n"); runCatching { app.vocr.VocrApp.note("longshot: $t", null) } }

    /** Writes the last capture's fixed footer and returns the finished part files (in the app cache). */
    fun finish(): List<File> {
        if (frames == 1) writer.add(prev, 0, h) else addRows(prev, h - max(ftrPrev, 0), h, 0, lastFtr)   // single capture: no pair, so no band was ever detected
        if (chrome.isNotEmpty()) note("OCR text leaves out screen chrome (kept in the image): " + chrome.joinToString(", ") { "rows ${it[0]}..${it[1]}" })
        if (marks.isNotEmpty()) note("OCR text carries ${marks.size} marker(s) at stitched rows: " + marks.joinToString(", ") { "${it.row}" })
        val f = writer.finish()
        prev.recycle()
        return f
    }

    fun discard() { runCatching { prev.recycle() }; writer.discard() }

    companion object {
        const val PART_H = 8000
        const val MAX_ROWS = 120000   // default; the user can change it in settings ("max_rows")

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

/** A note spliced into the OCR text at a stitched row: the capture is honest where the text may not be a clean read of one page state. */
class Mark(val row: Int, val text: String) {
    companion object {
        /** The strip starting at stitched row y0 (stripH tall) gets the markers whose row falls inside it, placed by line position. Pure, unit-tested. */
        fun inject(lines: List<app.vocr.core.Line>, marks: List<Mark>, y0: Int, stripH: Int): List<app.vocr.core.Line> {
            val here = marks.filter { it.row in y0 until y0 + stripH }
            if (here.isEmpty()) return lines
            val out = lines.toMutableList()
            for (mk in here.sortedBy { it.row }) {
                val frac = (mk.row - y0).toFloat() / stripH
                val idx = out.indexOfFirst { it.yFrac > frac }.let { if (it < 0) out.size else it }
                out.add(idx, app.vocr.core.Line(mk.text, frac))
            }
            return out
        }
    }
}

/** When the pixel match cannot prove a seam (a live page changed while scrolling), the offset can still be trusted if the retry captures keep agreeing on it. */
object SeamTrust {
    /**
     * Median of the last 3 recapture offsets when they agree within 8 px and the swipe calibration is sane (ratio 0.95..1.05); -1 = no position-only seam.
     * Costs nothing on clean captures: it only runs after 3 recaptures already failed to match.
     */
    fun positionOnlyDy(dys: List<Int>, ratio: Float): Int {
        if (ratio < 0.95f || ratio > 1.05f || dys.size < 3) return -1
        val last3 = dys.takeLast(3).sorted()
        if (last3[2] - last3[0] > 8) return -1
        return last3[1]
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

    fun breakPart() { if (fill > 0) flush() }
    fun finish(): List<File> { if (fill > 0) flush(); return files }
    fun discard() { runCatching { cur?.recycle() }; files.forEach { it.delete() }; files.clear() }
}

/** Saves screenshot parts to Download/ScrollRSI and, when asked, the OCR text of the full-resolution image next to them. */
object ShotSaver {
    class Result(val names: String, val images: List<android.net.Uri>, val textUri: android.net.Uri?, val text: String)

    /** Saves the parts. A single part keeps the plain name; several parts are numbered. */
    fun save(ctx: Context, base: String, parts: List<File>, withText: Boolean, chrome: List<IntArray> = emptyList(), marks: List<Mark> = emptyList(), singleFrame: Boolean = false): Result {
        val uris = ArrayList<android.net.Uri>()
        // Images are saved first under the timestamp name (nothing is lost if reading the text is cut short), then renamed after the text gives them a title.
        parts.forEachIndexed { i, f ->
            val name = if (parts.size == 1) "$base.png" else "$base part ${i + 1}.png"
            val r = app.vocr.core.Saver.saveFile(ctx, name, "image/png", f)
            if (r.uri == null) throw java.io.IOException(r.error)
            uris.add(r.uri)
        }
        var tt = ""
        if (withText) runCatching { tt = ocr(ctx, parts, chrome, marks, singleFrame) }
        var title = base
        if (tt.isNotBlank() && !app.vocr.core.Title.looksLikeList(tt)) runCatching { title = app.vocr.core.Saver.titleFor(ctx, tt, base) }   // a file list has no page title: keep the timestamp name
        var renamed = title != base
        if (renamed) {
            val ok = uris.mapIndexed { i, u -> app.vocr.core.Saver.rename(ctx, u, if (parts.size == 1) "$title.png" else "$title part ${i + 1}.png") }
            if (ok.any { !it }) { renamed = false; title = base }   // partial failure: report the names Android really holds is not possible, so keep the plain name for the text too
        }
        val names = parts.indices.map { i -> if (parts.size == 1) "${if (renamed) title else base}.png" else "${if (renamed) title else base} part ${i + 1}.png" }
        var tu: android.net.Uri? = null
        if (tt.isNotBlank()) runCatching { tu = app.vocr.core.Saver.saveAs(ctx, if (renamed) title else base, tt).uri }
        parts.forEach { it.delete() }
        return Result(names.joinToString(", "), uris, tu, tt)
    }

    /** OCR on native-resolution strips of about one screen with a small overlap (never on a downscaled copy), stitched like a video job. */
    private fun ocr(ctx: Context, parts: List<File>, chrome: List<IntArray>, marks: List<Mark>, singleFrame: Boolean): String {
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
                val found = eng.recognize(img)
                val kept = if (chrome.isEmpty()) found else found.filter { ln -> !ChromeMap.contains(chrome, y0 + (ln.y0 + ln.y1) / 2) }
                perStrip.add(Mark.inject(kept.map { app.vocr.core.Tokens.repairLine(eng, img, it) }.map { app.vocr.core.Line(app.vocr.core.Clean.text(it.text), (it.y0 + it.y1) / 2f / img.h) }.filter { app.vocr.core.Clean.keep(it.text) }, marks, y0, y1 - y0))
                if (y1 >= total) break
                y0 = y1 - ov
            }
            val sp = app.vocr.core.Spacing.load(ctx.assets.open("words.txt").use { String(it.readBytes(), Charsets.UTF_8) }.split("\n"))
            val merged = app.vocr.core.Stitch.merge(perStrip.map { st -> st.map { sp.fix(it.text) } }).map { app.vocr.core.Tokens.fixLine(app.vocr.core.Fix.lookalikes(it)) }
            return app.vocr.core.Flags.apply(merged, singleFrame).joinToString("\n")
        } finally { eng.close(); app.vocr.core.Pipeline.gate.unlock() }
    }
}


/** Remembers flat strips (first row, rows, value) seen in the captures of one run. A strip seen a second time at the same screen row is a host strip, whether or not it was present in the capture in between. */
class BandTracker {
    private val seen = HashMap<Long, Int>()
    private fun key(b: IntArray) = b[0] * 1_000_000L + b[1] * 1_000L + b[2] / 3
    fun count(b: IntArray?): Int = if (b == null) 0 else seen[key(b)] ?: 0
    /** Records a sighting (or none) and returns the strip's first row when it should be cropped, else -1. */
    fun see(b: IntArray?): Int { if (b == null) return -1; val c = (seen[key(b)] ?: 0) + 1; seen[key(b)] = c; return if (c >= 2) b[0] else -1 }
}

/**
 * Finds a bar that floats over the page at the same screen rows in every capture while the page moves under it: a chat composer, a glass toolbar, a bottom sheet.
 * Such a bar is not found by comparing two captures row by row, because a translucent bar lets the moving page show through and the match flips from pair to pair.
 * Instead, for each pair with a clear scroll offset dy, every row is scored by how many of its edge pixels (text, borders, icons) are identical at the SAME screen position in both
 * captures but are NOT explained by the page moving by dy. The evidence adds up over the whole run, so one clear sighting is enough and a later unlucky pair cannot undo it.
 * The overlay is the chain of such rows that starts next to the system bar and reaches up with gaps of at most GAP rows (the bar's flat parts have no edges). Pure byte arrays, unit-tested.
 */
class OverlayTracker(private val w: Int, private val h: Int, private val sysTop: Int = 0, private val sysBottom: Int = 0) {
    private val hits = IntArray(h); private val elig = IntArray(h)
    var pairs = 0; private set
    private val xe = w * ShotMatch.EDGE / 100

    /** Adds one pair: a = previous capture, b = current capture, the page moved down by dy rows between them. Pairs with a small dy cannot tell a fixed bar from the page and are skipped. */
    fun update(a: ByteArray, b: ByteArray, dy: Int): Boolean {
        if (dy < MIN_DY || dy >= h) return false
        for (y in 0 until h) {
            // Judge each row from the capture where the page's own position of that row can be checked in the other capture: the current one for rows whose content was
            // already visible lower down (y + dy inside the previous capture), the previous one for the rest (the same rows are higher up in the current capture).
            val fromCur = y + dy < h
            if (!fromCur && y - dy < 0) continue
            val base = if (fromCur) b else a; val other = if (fromCur) a else b
            val sy = if (fromCur) y + dy else y - dy
            var edges = 0; var stat = 0; var x = 0
            val ro = y * w; val so = sy * w
            while (x + 4 < xe) {
                val c0 = base[ro + x].toInt() and 255
                val edge = abs(c0 - (base[ro + x + 4].toInt() and 255)) > 18 || (y + 3 < h && abs(c0 - (base[ro + 3 * w + x].toInt() and 255)) > 18)
                if (edge) {
                    edges++
                    val same = abs(c0 - (other[ro + x].toInt() and 255)) <= 8
                    val explained = abs(c0 - (other[so + x].toInt() and 255)) <= 14   // the page moved by dy and this pixel moved with it
                    if (same && !explained) stat++
                }
                x += 3
            }
            if (edges >= 6) { elig[y]++; if (stat >= 6 && stat * 100 >= edges * 35) hits[y]++ }
        }
        pairs++
        return true
    }

    private fun hit(y: Int) = hits[y] >= 1 && hits[y] * 2 >= elig[y]

    /** Rows from the bottom of the screen up to the top of the overlay (0 when none). */
    fun footer(): Int {
        if (pairs == 0) return 0
        val start = h - sysBottom - 1; val limit = h - h * CAP / 100
        var last = -1; var gap = 0; var n = 0; var y = start
        while (y >= limit) {
            if (hit(y)) { last = y; gap = 0; n++ } else { gap++; if (gap > GAP) break }
            y--
        }
        if (last < 0 || n < MIN_ROWS || start - last < MIN_SPAN) return 0
        return h - max(0, last - MARGIN)
    }

    /** Rows from the top of the screen down to the bottom of the overlay (0 when none). */
    fun header(): Int {
        if (pairs == 0) return 0
        val start = sysTop; val limit = h * CAP / 100
        var last = -1; var gap = 0; var n = 0; var y = start
        while (y < limit) {
            if (hit(y)) { last = y; gap = 0; n++ } else { gap++; if (gap > GAP) break }
            y++
        }
        if (last < 0 || n < MIN_ROWS || last - start < MIN_SPAN) return 0
        return min(h, last + MARGIN + 1)
    }

    companion object { const val MIN_DY = 40; const val GAP = 110; const val MIN_ROWS = 4; const val MIN_SPAN = 20; const val MARGIN = 24; const val CAP = 35 }
}

/** Pure offset finder for two captures (grey bytes, w x h). Kept free of Android classes so it can be unit-tested. */
object ShotMatch {
    /** Columns right of this percentage of the width are not scored: the screen-recorder bubble, a picture-in-picture video or an edge panel often sit there and change by themselves. */
    const val EDGE = 88
    /** Cutting a bottom bar that vanishes or floats over the content: ON with the conservative rules of hiddenFooter (build 85). Set false to turn off. */
    const val HIDDEN_FOOTER = true
    enum class Status { OK, END, MISMATCH }

    /** Where to append the new capture: [start row, bottom row, new "footer edge" distance]. The previous slice ended `ftrPrev` rows above the previous capture's bottom, which is row h - ftrPrev - dy of the new capture.
     *  When the footer grows by more than dy between two pairs, bottom falls above that row: nothing new is appended and the written end stays at start (not bottom), so the next pair does not repeat rows. */
    /** Rows at the bottom of the scrolling area that are left out of every append (the chat's fade and its "jump to latest" button), so the next capture's clean rows are used instead. */
    const val FADE = 120

    /** Like [seam], but the written end stays `fade` rows above the footer. Returns [start, bottomW, new ftrPrev, prevFrom, prevTo] where rows start..bottomW of the new capture are appended.
     *  When the new capture cannot reach back to the written end (a drag of almost the whole band) rows prevFrom..prevTo of the previous capture are written first, so no row is lost. */
    fun seamFade(h: Int, ftrPrev: Int, ftr: Int, dy: Int, top: Int, fade: Int, prevFtr: Int): IntArray {
        val raw = h - ftrPrev - dy
        val start = max(raw, top)
        val bottomW = max(h - ftr - fade, 0)
        val pFrom = h - ftrPrev
        val pTo = if (raw < top) min(top + dy, h - prevFtr) else pFrom
        return intArrayOf(start, bottomW, h - max(start, bottomW), pFrom, pTo)
    }

    fun seam(h: Int, ftrPrev: Int, ftr: Int, dy: Int, top: Int): IntArray {
        val bottom = h - ftr
        val start = max(h - ftrPrev - dy, top)
        return intArrayOf(start, bottom, if (start > bottom) h - start else ftr)
    }

    /** A bar at the bottom of the previous capture that is gone in the next one (X and Chrome hide it while you scroll down). Conservative: the rows of the previous capture that do not match
     *  the shifted next capture must form ONE cluster that starts at the footer edge (within 6 rows), no taller than 10% of the screen, and the 60 rows right above the cluster must match well:
     *  that proves the offset is right (a wrong offset on a list page makes everything mismatch). The right 15% of the width is ignored (floating buttons). A weak match (`weak`) is ignored.
     *  Returns how many rows above `bottom` to cut (0 when none). */
    fun hiddenFooter(a: ByteArray, b: ByteArray, w: Int, h: Int, dy: Int, top: Int, bottom: Int, weak: Boolean = false): Int {
        if (weak) return 0
        val xe = w * 85 / 100
        val cap = h / 10; val zone = 60
        fun diff(y: Int): Double {
            val yb = y - dy
            if (y < 0 || y >= h || yb < top || yb >= h) return 255.0
            var sum = 0L; var n = 0; var x = 0
            while (x < xe) { sum += abs((a[y * w + x].toInt() and 255) - (b[yb * w + x].toInt() and 255)); n++; x += 3 }
            return sum.toDouble() / n
        }
        // the cluster must start at the footer edge
        var y = bottom - 1
        var firstMis = -1
        while (y >= bottom - 6) { if (diff(y) > 15.0) { firstMis = y; break }; y-- }
        if (firstMis < 0) return 0
        // walk up until `zone` quiet rows in a row: the last mismatching row before them is the top of the bar
        var topMis = firstMis; var quiet = 0; y = firstMis - 1
        var count = 1
        while (y >= 0 && quiet < zone) {
            if (bottom - y > cap + zone) return 0
            val d = diff(y)
            if (d > 15.0) { topMis = y; quiet = 0; count++ } else if (d < 6.0) quiet++ else quiet = 0
            y--
        }
        if (quiet < zone) return 0
        val rows = bottom - topMis
        return if (count >= 4 && rows <= cap) rows else 0
    }

    /** A flat band: 6 to 20 rows in the lower 40% of the screen, one single grey value across the left 90% of the width, clearly different from the rows above and below.
     *  Returns (first row, row count, value) or null. Used to find a host or system strip that sits at the same screen row in every capture. */
    fun flatBand(a: ByteArray, w: Int, h: Int): IntArray? {
        val xe = w * 90 / 100
        fun rowVal(y: Int): Int {   // the row's value when flat (at least 97% of the samples within 3 grey levels of the middle one), else -1
            val o = y * w; val v = a[o + xe / 2].toInt() and 255
            var n = 0; var bad = 0; var x = 0
            while (x < xe) { n++; if (abs((a[o + x].toInt() and 255) - v) > 3) bad++; x += 3 }
            return if (bad * 100 <= n * 3) v else -1
        }
        var y = h * 60 / 100
        while (y < h) {
            val v = rowVal(y)
            if (v in 30..200) {
                var e = y + 1
                while (e < h && rowVal(e).let { it >= 0 && abs(it - v) <= 3 }) e++
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
        val keep = (rows.size * 7) / 10
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
            return if (still < 3.0) Match(Status.END, 0, bestScore, still, hdr, ftr) else Match(Status.MISMATCH, max(best, 0), bestScore, still, hdr, ftr)
        }
        if (still < 255.0 && bestScore > still * 0.8 && still < 3.0) return Match(Status.END, 0, bestScore, still, hdr, ftr)   // no motion: the end of the page
        return Match(Status.OK, best, bestScore, still, hdr, ftr)
    }
}
