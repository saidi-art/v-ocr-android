package app.vocr.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import kotlin.math.min

data class StripInfo(val file: File, val t: Double, val kind: String, val srcTop: Int = 0, val srcH: Int = 0)
data class Progress(val stage: String, val done: Int, val total: Int, val etaS: Int = -1, val live: List<String> = emptyList(), val lines: Int = 0, val frac: Float = -1f)
data class Result(val strips: List<StripInfo>, val framesTotal: Int, val dup: Int, val full: Int, val cropped: Int, val seconds: Int, val ms: Long, val timing: String = "", val wholeSkips: Int = 0, val regionSkips: Int = 0, val fpsInfo: String = "", val topBandDet: Int = 0, val bottomBandDet: Int = 0, val detH: Int = 0)

/** Per-run stats block (frame side from the Result, reading side from the ReadResult). Appended to the saved text and shown on the result screen. */
object RunStats {
    fun footer(res: Result, rd: ReadResult): String {
        val accum = res.dup - res.wholeSkips - res.regionSkips
        val ocr = res.strips.size
        val rs = rd.ms / 1000.0
        return buildString {
            append("--- ScrollRSI run stats ---\n")
            append("App: ScrollRSI ${app.vocr.VocrApp.version()}\n")
            append("Video: ${res.seconds}s. Frames sampled: ${res.framesTotal}.\n")
            append("Skipped: ${res.dup} total = ${res.wholeSkips} whole-screen still + ${res.regionSkips} region-gated (video up top) + $accum accumulating while scrolling.\n")
            append("Kept for OCR: $ocr strips (${res.full} full, ${res.cropped} cropped).\n")
            append("Frame analysis total: ${res.ms / 1000.0}s. ${res.timing}\n")
            append("OCR: %.1fs for $ocr strips (%.2f strips/s). Lines read ${rd.rawLines}, after overlap removal ${rd.mergedLines}.\n".format(rs, if (rs > 0) ocr / rs else 0.0))
            if (res.fpsInfo.isNotEmpty()) append("Sampling: ${res.fpsInfo}\n")
            app.vocr.VocrApp.lastStop()?.let { append("Last auto-scroll stop: $it") }
            app.vocr.VocrApp.lastError()?.let { append("Last diagnostic note on this device:\n$it\n") }
        }
    }
}

object Pipeline {
    const val FRAME_W = 720      // frames are decoded at this width
    const val DET_W = 360        // scroll detection runs at this width

    fun toGray(bm: Bitmap): Gray {
        val w = bm.width; val h = bm.height
        val px = IntArray(w * h); bm.getPixels(px, 0, w, 0, 0, w, h)
        val d = FloatArray(w * h)
        for (i in px.indices) { val c = px[i]; d[i] = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c) }
        return Gray(w, h, d)
    }

    /** One heavy job (decode or OCR) at a time across the app and the widget: parallel jobs only slow each other down. */
    val gate = java.util.concurrent.locks.ReentrantLock(true)

    /** Each job gets its own scratch folder so a second job can never wipe the first one's strips. */
    @Synchronized private fun newJobDir(ctx: Context): File {
        val now = System.currentTimeMillis()
        ctx.cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("job") && now - it.lastModified() > 3 * 3600_000L }?.forEach { it.deleteRecursively() }
        return File(ctx.cacheDir, "job-" + System.nanoTime()).apply { mkdirs() }
    }

    fun run(ctx: Context, uri: Uri?, onProgress: (Progress) -> Unit, cancelled: () -> Boolean = { false }, scrollTrace: ScrollTrace? = null): Result {
        gate.lock()
        try { return runLocked(ctx, uri, onProgress, cancelled, scrollTrace) } finally { gate.unlock() }
    }

    private fun runLocked(ctx: Context, uri: Uri?, onProgress: (Progress) -> Unit, cancelled: () -> Boolean, scrollTrace: ScrollTrace?): Result {
        app.vocr.VocrApp.clearNote()   // the footer carries this job's diagnostic note, not a stale one
        val info = runCatching { Decoder.probe(ctx, uri) }.getOrNull()
        if (info != null && info.rotation == 0) {
            val r = runDecoded(ctx, uri, info, onProgress, cancelled, scrollTrace)
            if (r != null) return r
        }
        return runRetriever(ctx, uri, onProgress, cancelled)
    }

    /** Sequential MediaCodec path: one decode pass, luma-only analysis, RGB only for kept strips. */
    private fun runDecoded(ctx: Context, uri: Uri?, info: Decoder.Info, onProgress: (Progress) -> Unit, cancelled: () -> Boolean, scrollTrace: ScrollTrace?): Result? {
        val t0 = System.currentTimeMillis()
        val dir = newJobDir(ctx)
        val fw = min(FRAME_W, info.width); val fh = (info.height.toLong() * fw / info.width).toInt()
        val dw = DET_W.coerceAtMost(fw); val dh = (fh.toLong() * dw / fw).toInt()
        val seconds = (info.durationUs / 1_000_000).toInt().coerceAtLeast(1)
        val fps = maxOf(1.0, min(3.0, 700.0 / seconds))
        val sb = StripBuilder(overlapPx = (36 * dw / 720.0).toInt().coerceAtLeast(12))
        val strips = ArrayList<StripInfo>()
        var last: YuvFrame? = null; var lastT = 0.0; var got = 0
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val slots = java.util.concurrent.Semaphore(4)
        fun save(f: YuvFrame, top: Int, bot: Int, t: Double, kind: String) {
            val file = File(dir, "s%04d_%s.jpg".format(strips.size, kind))
            strips.add(StripInfo(file, t, kind, top, fh))
            slots.acquireUninterruptibly()
            pool.execute {
                try {
                    val px = f.argb(fw, fh)
                    val bm = Bitmap.createBitmap(px, fw, fh, Bitmap.Config.ARGB_8888)
                    val c = if (top == 0 && bot == fh) bm else Bitmap.createBitmap(bm, 0, top, fw, bot - top)
                    file.outputStream().use { c.compress(Bitmap.CompressFormat.JPEG, 75, it) }
                    if (c !== bm) c.recycle(); bm.recycle()
                } finally { slots.release() }
            }
        }
        val rate = RateCtl(fps)
        val bands = ScrollBands(dw, dh)   // fixed top/bottom furniture (chat header, composer), proven by traced movement
        var lastG: Gray? = null; var lastGT = 0.0; var dyTraced = 0
        var fuzzySkips = 0; val recentFulls = ArrayList<Long>()   // hashes of the last few kept whole frames: near-identical repeats are dropped
        // End-of-page rule: no matched scroll for endStopSec seconds of video (after real scrolling happened) = the page ended.
        // Frames after that are only the same last screen (tooltips, blinking UI) and would be OCR'd as duplicates.
        val endStopSec = app.vocr.capture.CaptureService.prefs(ctx).getInt("endstopsec", 30)
        var seenMatches = 0; var tProgress = 0.0; var stripsAtProgress = 0; var stoppedAt = -1.0
        var fpsMax = fps; var fpsSum = 0.0; var fpsHigh = 0; val trace = StringBuilder(); var nextTrace = 0.0
        var analysisMs = 0L; var saveMs = 0L; var grayN = 0L; var hashN = 0L; var matchN = 0L; var progN = 0L
        val ok = Decoder.decode(ctx, uri, fps, { cancelled() || stoppedAt >= 0 }) { t, f ->
            got++
            val ta = System.nanoTime()
            onProgress(Progress("Reading frames", (t / seconds * 100).toInt().coerceAtMost(100), 100))
            val tp = System.nanoTime(); progN += tp - ta
            val g = f.gray(dw, dh)
            val t1 = System.nanoTime()
            val hh = Hash.phash(YuvFrame.shrink(g, 32, 32))
            val t2 = System.nanoTime()
            val dyDet = if (scrollTrace == null || lastG == null) -1 else scrollTrace.pxBetween((lastGT * 1000).toLong(), (t * 1000).toLong()).let { if (it < 0) -1 else it * dh / scrollTrace.screenH }
            val dec = if (scrollTrace != null) sb.pushTraced(hh, g, dyDet) else sb.push(hh, g)
            if (dyDet >= ScrollBands.MIN_DY) bands.update(lastG!!, g, dyDet)
            lastG = g; lastGT = t
            grayN += t1 - tp; hashN += t2 - t1; matchN += System.nanoTime() - t2
            val tb = System.nanoTime(); analysisMs += (tb - ta) / 1_000_000
            when (dec) {
                is Decision.Skip -> {}
                is Decision.Full -> {
                    // the scroll match failed but the screen barely changed: same region again - keep one copy, skip the echo
                    if (recentFulls.any { Hash.similarity(hh, it) >= 0.965 }) fuzzySkips++
                    else {
                        // trim the fixed header/footer (seen consistently) so the OCR spends time on the scrolling band only
                        val bt = sb.bandTop; val bf = sb.bandFooter; val sc = fh.toFloat() / dh
                        if (bt >= 0 && bf >= 0 && dh - bt - bf > dh / 3) save(f, (bt * sc).toInt(), fh - (bf * sc).toInt(), t, "full") else save(f, 0, fh, t, "full")
                        recentFulls.add(hh); if (recentFulls.size > 8) recentFulls.removeAt(0)
                    }
                }
                is Decision.Crop -> {
                    val sc = fh.toFloat() / dh
                    val top = (dec.top * sc).toInt().coerceIn(0, fh - 1); val bot = (dec.bottom * sc).toInt().coerceIn(top + 1, fh)
                    if (dyDet > 0) dyTraced++
                    save(f, top, bot, t, "crop")
                }
            }
            saveMs += (System.nanoTime() - tb) / 1_000_000
            last = f; lastT = t
            // a changed frame we couldn't match (fast scroll) means we skipped content: sample faster until matching again
            val newFps = rate.update(sb)
            fpsSum += rate.cur; if (rate.cur > fpsMax) fpsMax = rate.cur; if (rate.cur > fps * 1.5) fpsHigh++
            if (t >= nextTrace) { if (trace.length < 400) trace.append("%d:%.1f ".format(t.toInt(), rate.cur)); nextTrace = t + maxOf(10.0, seconds / 20.0) }
            if (sb.scrollMatches != seenMatches) { seenMatches = sb.scrollMatches; tProgress = t; stripsAtProgress = strips.size }
            if (endStopSec > 0 && seenMatches >= 5 && t - tProgress > endStopSec && stoppedAt < 0) stoppedAt = t
            newFps
        }
        if (!ok && got == 0) return null
        // drop the strips made during the stalled tail, but keep the first one (the final screen state)
        val droppedFiles = ArrayList<File>()
        if (stoppedAt >= 0 && strips.size > stripsAtProgress + 1) {
            while (strips.size > stripsAtProgress + 1) droppedFiles.add(strips.removeAt(strips.size - 1).file)
        }
        val fin = sb.flush(); val lf = last
        if (fin is Decision.Crop && lf != null) {
            val sc = fh.toFloat() / dh
            val top = (fin.top * sc).toInt().coerceIn(0, fh - 1); val bot = (fin.bottom * sc).toInt().coerceIn(top + 1, fh)
            save(lf, top, bot, lastT, "crop")
        }
        val tw = System.currentTimeMillis()
        pool.shutdown(); pool.awaitTermination(120, java.util.concurrent.TimeUnit.SECONDS)
        val waitMs = System.currentTimeMillis() - tw
        for (df in droppedFiles) df.delete()
        onProgress(Progress("Done", 100, 100))
        val total = System.currentTimeMillis() - t0
        val bandTop = bands.topBand(); val bandBot = bands.bottomBand()
        val traceNote = if (scrollTrace == null) "" else " Phone trace: ${scrollTrace.events()} events" +
            (if (dyTraced > 0) ", $dyTraced strip(s) placed by it" else "") +
            (if (bandTop > 0 || bandBot > 0) ", fixed bands top=$bandTop bottom=$bandBot det rows (${bands.pairs} pairs)" else "") + "."
        return Result(strips, sb.total, sb.dup, sb.full, sb.cropped, seconds, total, "analysis ${analysisMs}ms (progress cb ${progN/1_000_000}, gray ${grayN/1_000_000}, hash ${hashN/1_000_000}, match ${matchN/1_000_000}), save queue ${saveMs}ms + final wait ${waitMs}ms, decode+other ${total - analysisMs - saveMs - waitMs}ms",
            sb.wholeSkips, sb.regionSkips,
            "base %.1f fps, max %.1f, mean %.1f, frames sampled above 1.5x base: %d. Trace (sec:fps): %s".format(fps, fpsMax, if (got > 0) fpsSum / got else fps, fpsHigh, trace.toString().trim()) + " Fuzzy-dropped $fuzzySkips repeated full strip(s)." + traceNote +
            (if (stoppedAt >= 0) " End of page: no scroll matched for ${endStopSec}s, stopped reading at %.0fs of %ds, dropped ${droppedFiles.size} tail strips.".format(stoppedAt, seconds) else ""),
            bandTop, bandBot, dh)
    }

    private fun runRetriever(ctx: Context, uri: Uri?, onProgress: (Progress) -> Unit, cancelled: () -> Boolean): Result {
        val t0 = System.currentTimeMillis()
        val dir = newJobDir(ctx)
        val r = MediaMetadataRetriever()
        if (uri != null) r.setDataSource(ctx, uri)
        else ctx.assets.openFd("demo.mp4").use { r.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: FRAME_W
        val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1600
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val (ow, oh) = if (rot == 90 || rot == 270) vh to vw else vw to vh
        val fw = min(FRAME_W, ow); val fh = (oh.toLong() * fw / ow).toInt()
        val dw = DET_W.coerceAtMost(fw); val dh = (fh.toLong() * dw / fw).toInt()
        val seconds = (durMs / 1000).toInt().coerceAtLeast(1)
        // same budget as web v1: up to 3 fps, at most ~700 frames, at least 1 fps
        val fps = maxOf(1.0, min(3.0, 700.0 / seconds))
        val n = (seconds * fps).toInt().coerceAtLeast(1)
        val sb = StripBuilder(overlapPx = (36 * dw / 720.0).toInt().coerceAtLeast(12))
        val strips = ArrayList<StripInfo>()
        val recentFulls = ArrayList<Long>()
        var lastFrame: Bitmap? = null
        var lastT = 0.0
        fun save(bm: Bitmap, t: Double, kind: String) {
            val f = File(dir, "s%04d_%s.jpg".format(strips.size, kind))
            f.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            strips.add(StripInfo(f, t, kind, 0, bm.height))
        }
        for (i in 0 until n) {
            if (cancelled()) break
            val t = i / fps
            onProgress(Progress("Reading frames", i, n))
            val frame = r.getScaledFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, fw, fh) ?: continue
            val det = Bitmap.createScaledBitmap(frame, dw, dh, true)
            val g = toGray(det); if (det !== frame) det.recycle()
            val small = Bitmap.createScaledBitmap(frame, 32, 32, true)
            val hash = Hash.phash(toGray(small)); small.recycle()
            when (val dec = sb.push(hash, g)) {
                is Decision.Skip -> { lastFrame?.recycle(); lastFrame = frame }
                is Decision.Full -> {
                    if (recentFulls.any { Hash.similarity(hash, it) >= 0.965 }) { frame.recycle() }   // same screen region again: keep one copy
                    else { save(frame, t, "full"); recentFulls.add(hash); if (recentFulls.size > 8) recentFulls.removeAt(0) }
                    lastFrame?.recycle(); lastFrame = frame
                }
                is Decision.Crop -> {
                    val sc = frame.height.toFloat() / dh
                    val top = (dec.top * sc).toInt().coerceIn(0, frame.height - 1)
                    val bot = (dec.bottom * sc).toInt().coerceIn(top + 1, frame.height)
                    save(Bitmap.createBitmap(frame, 0, top, frame.width, bot - top), t, "crop")
                    lastFrame?.recycle(); lastFrame = frame
                }
            }
            lastT = t
        }
        val fin = sb.flush()
        val lf = lastFrame
        if (fin is Decision.Crop && lf != null && !lf.isRecycled) {
            val sc = lf.height.toFloat() / dh
            val top = (fin.top * sc).toInt().coerceIn(0, lf.height - 1)
            val bot = (fin.bottom * sc).toInt().coerceIn(top + 1, lf.height)
            save(Bitmap.createBitmap(lf, 0, top, lf.width, bot - top), lastT, "crop")
        }
        lf?.recycle(); r.release()
        onProgress(Progress("Done", n, n))
        return Result(strips, sb.total, sb.dup, sb.full, sb.cropped, seconds, System.currentTimeMillis() - t0)
    }
}
