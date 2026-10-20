package app.vocr.core

import android.content.Context
import android.graphics.BitmapFactory

data class Line(val text: String, val yFrac: Float)

/** Fixed app chrome (status bar, headers, nav bars) repeats at the same place in many strips: keep it once. */
object Chrome {
    /** A line that shows up at the same height in many strips (and with the same text) is fixed screen furniture, not content. */
    fun strip(strips: List<List<Line>>): List<List<Line>> {
        if (strips.size < 3) return strips
        val need = maxOf(3, (strips.size * 0.3).toInt())
        // Linear-time index (was quadratic: every line against every line of every strip, minutes at 2,500 strips).
        // A line counts in a strip when that strip has a line with the same normalized text within about one height bucket (0.012 of the screen).
        val bw = 0.012f
        val idx = HashMap<String, HashSet<Int>>()
        fun key(t: String, b: Int) = t + "@" + b
        for ((si, lines) in strips.withIndex()) for (l in lines) {
            val nk = Stitch.normKey(l.text); if (nk.isEmpty()) continue
            idx.getOrPut(key(nk, (l.yFrac / bw).toInt())) { HashSet() }.add(si)
        }
        val seenOnce = HashSet<String>()
        return strips.mapIndexed { _, lines ->
            lines.filter { l ->
                val nk = Stitch.normKey(l.text)
                val b = (l.yFrac / bw).toInt()
                val sets = listOf(b - 1, b, b + 1).mapNotNull { idx[key(nk, it)] }
                val n = if (sets.sumOf { it.size } < need) 0 else if (sets.size == 1) sets[0].size else HashSet<Int>().also { u -> sets.forEach { u.addAll(it) } }.size
                if (n < need) return@filter true
                val k2 = nk + "@" + (l.yFrac * 80).toInt()
                seenOnce.add(k2)    // keep the first occurrence only
            }
        }
    }
}

/** Confidence-free, conservative look-alike fixes inside number-like tokens (O->0, l->1). */
object Fix {
    private val numeric = Regex("^[0-9OoIl|.,:;/\\-]+$")
    fun lookalikes(line: String): String = line.split(' ').joinToString(" ") { t ->
        if (numeric.matches(t) && t.count { it.isDigit() } >= 2)
            t.map { when (it) { 'O', 'o' -> '0'; 'l', 'I', '|' -> '1'; else -> it } }.joinToString("") else t
    }
}

data class ReadResult(val text: String, val strips: Int, val rawLines: Int, val mergedLines: Int, val ms: Long, val detail: String, val savedTitle: String = "", val savedOk: Boolean = false)

class JobCancelled : RuntimeException("Cancelled")

object Reader {
    /** Device speed test: recognise one mid-clip strip with different ONNX thread counts and report ms per count. */
    fun bench(ctx: Context, strips: List<StripInfo>, onLine: (String) -> Unit) {
        val s = strips[strips.size / 2]
        val bm = BitmapFactory.decodeFile(s.file.path) ?: return
        val px = IntArray(bm.width * bm.height); bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        for (k in px.indices) px[k] = px[k] and 0xFFFFFF
        val img = Rgb(bm.width, bm.height, px); bm.recycle()
        onLine("cores=${Runtime.getRuntime().availableProcessors()}  strip ${img.w}x${img.h}")
        for (t in intArrayOf(2, 3, 4, 6, 8)) {
            val e = load(ctx, t)
            try {
                e.recognize(img)   // warm-up
                val a = System.nanoTime(); e.recognize(img); e.recognize(img)
                onLine("threads $t: ${(System.nanoTime() - a) / 2_000_000} ms per strip")
            } finally { e.close() }
        }
        // execution providers: same strip, text compared with the CPU result
        val base = load(ctx, 4).use1 { e -> e.recognize(img); e.recognize(img).map { it.text } }
        onLine("cpu 4 threads text: ${base.size} lines")
        for ((prov, t) in listOf("xnnpack" to 4, "nnapi" to 4)) {
            try {
                val e = load(ctx, t, prov)
                try {
                    e.recognize(img)   // warm-up (NNAPI compiles here)
                    val a = System.nanoTime(); val r = e.recognize(img); e.recognize(img)
                    val ms = (System.nanoTime() - a) / 2_000_000
                    val same = r.map { it.text } == base
                    val diff = r.map { it.text }.zip(base).count { it.first != it.second } + Math.abs(r.size - base.size)
                    onLine("$prov: $ms ms per strip, ${r.size} lines, " + if (same) "text identical to CPU" else "$diff lines differ from CPU")
                } finally { e.close() }
            } catch (x: Throwable) { onLine("$prov failed: ${x.message}") }
        }
    }

    private fun <T> OcrEngine.use1(f: (OcrEngine) -> T): T = try { f(this) } finally { close() }

    fun load(ctx: Context, threads: Int = 4, provider: String = "cpu"): OcrEngine {
        val det = ctx.assets.open("ocr/det.onnx").use { it.readBytes() }
        val rec = ctx.assets.open("ocr/rec.onnx").use { it.readBytes() }
        val keys = ctx.assets.open("ocr/keys.txt").use { String(it.readBytes(), Charsets.UTF_8) }.split("\n")
        return OcrEngine(det, rec, keys, threads, provider)
    }

    fun readAll(ctx: Context, strips: List<StripInfo>, fast: Boolean = false, cancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit): ReadResult {
        Pipeline.gate.lock()
        try { return readAllLocked(ctx, strips, fast, cancelled, onProgress) } finally { Pipeline.gate.unlock() }
    }

    /** Lab data (151 strips): area-weighted ETA ran 5-19% short late in a job; strip count alone ran 16-38% short. */
    private const val ETA_BIAS = 1.10
    private fun readAllLocked(ctx: Context, strips: List<StripInfo>, fast: Boolean, cancelled: () -> Boolean, onProgress: (Progress) -> Unit): ReadResult {
        val t0 = System.currentTimeMillis()
        val eng = load(ctx)
        if (fast) { eng.recH = 42; eng.maxSide = 704; eng.adaptive = false }   // ~25% faster, ~2-3 points less accurate on dense text
        // strip areas give the time estimate (recognition cost follows the amount of text, which follows area)
        val areas = strips.map { s -> val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(s.file.path, o); (o.outWidth.toLong() * o.outHeight).coerceAtLeast(1L) }
        val totalArea = areas.sum().coerceAtLeast(1L); var doneArea = 0L; var liveLines = 0; var live: List<String> = emptyList()
        val tRead = System.currentTimeMillis()
        val perStrip = ArrayList<List<Line>>()
        try {
            for ((i, s) in strips.withIndex()) {
                if (cancelled()) throw JobCancelled()   // user pressed Cancel mid-read: stop before the next strip
                val el = System.currentTimeMillis() - tRead
                val eta = if (doneArea > 0) ((el.toDouble() / doneArea * (totalArea - doneArea) * ETA_BIAS) / 1000).toInt() else -1
                onProgress(Progress("Reading text", i, strips.size, eta, live, liveLines, doneArea.toFloat() / totalArea))
                val bm = BitmapFactory.decodeFile(s.file.path) ?: continue
                val px = IntArray(bm.width * bm.height); bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
                for (k in px.indices) px[k] = px[k] and 0xFFFFFF
                val img = Rgb(bm.width, bm.height, px); bm.recycle()
                val ls = eng.recognize(img).map { Tokens.repairLine(eng, img, it) }.map { Line(Clean.text(it.text), (it.y0 + it.y1) / 2f / img.h) }.filter { Clean.keep(it.text) }
                perStrip.add(ls); doneArea += areas[i]; liveLines += ls.size; live = ls.takeLast(6).map { it.text }
            }
            onProgress(Progress("Stitching", strips.size, strips.size))
            val raw = perStrip.sumOf { it.size }
            val cleaned = Chrome.strip(perStrip)
            val sp = Spacing.load(ctx.assets.open("words.txt").use { String(it.readBytes(), Charsets.UTF_8) }.split("\n"))
            val merged = Stitch.merge(cleaned.map { st -> st.map { sp.fix(it.text) } }).map { Tokens.fixLine(Fix.lookalikes(it)) }
            val ms = System.currentTimeMillis() - t0
            return ReadResult(merged.joinToString("\n"), strips.size, raw, merged.size, ms,
                "detect ${eng.detMs}ms, recognize ${eng.recMs}ms, post ${eng.postMs}ms")
        } finally { eng.close() }
    }
}

/** Text hygiene: drop CJK/symbol junk the model hallucinates on wallpaper and icons, fix punctuation/digit splits. */
object Clean {
    private val cjk = Regex("[\\u2E80-\\u9FFF\\uA000-\\uD7FF\\uF900-\\uFAFF\\uFF00-\\uFFEF\\u2190-\\u21FF\\u25A0-\\u27BF]")
    private val glued = Regex("([a-z])([.,;:!?])([A-Z][a-z])")
    private val num = Regex("^[0-9:.,/]+$")
    fun text(t: String): String {
        var s = cjk.replace(t, "").replace(Regex("\\s+"), " ").trim()
        s = glued.replace(s, "$1$2 $3")
        val toks = s.split(' ').toMutableList()
        var i = 0
        while (i + 1 < toks.size) {   // "1 0:1 3" -> "10:13" (narrow digit 1 leaves a pixel gap)
            val a = toks[i]; val b = toks[i + 1]
            if (num.matches(a) && num.matches(b) && (a == "1" || b == "1" || a.endsWith("1") && b.length == 1 || b.startsWith("1") && a.length == 1)) { toks[i] = a + b; toks.removeAt(i + 1) } else i++
        }
        return toks.joinToString(" ")
    }
    fun keep(t: String): Boolean = t.count { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } >= 2
}
