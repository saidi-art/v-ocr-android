package app.vocr.core

import android.content.Context
import android.graphics.BitmapFactory

data class Line(val text: String, val yFrac: Float)

/** Fixed app chrome (status bar, headers, nav bars) repeats at the same place in many strips: keep it once. */
object Chrome {
    fun strip(strips: List<List<Line>>): List<List<Line>> {
        if (strips.size < 3) return strips
        fun inTop(l: Line) = l.yFrac < 0.14f
        fun inBot(l: Line) = l.yFrac > 0.92f
        return strips.mapIndexed { si, lines ->
            lines.filter { l ->
                val top = inTop(l); val bot = inBot(l)
                if (!top && !bot) return@filter true
                val others = strips.indices.count { o -> o != si && strips[o].any { m -> (if (top) inTop(m) else inBot(m)) && Stitch.sim(l.text, m.text) >= 0.8 } }
                val repeated = others >= 2
                !repeated || (top && si == 0) || (bot && si == strips.size - 1)
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

data class ReadResult(val text: String, val strips: Int, val rawLines: Int, val mergedLines: Int, val ms: Long, val detail: String)

object Reader {
    fun load(ctx: Context): OcrEngine {
        val det = ctx.assets.open("ocr/det.onnx").use { it.readBytes() }
        val rec = ctx.assets.open("ocr/rec.onnx").use { it.readBytes() }
        val keys = ctx.assets.open("ocr/keys.txt").use { String(it.readBytes(), Charsets.UTF_8) }.split("\n")
        return OcrEngine(det, rec, keys)
    }

    fun readAll(ctx: Context, strips: List<StripInfo>, onProgress: (Progress) -> Unit): ReadResult {
        val t0 = System.currentTimeMillis()
        val eng = load(ctx)
        val perStrip = ArrayList<List<Line>>()
        try {
            for ((i, s) in strips.withIndex()) {
                onProgress(Progress("Reading text", i, strips.size))
                val bm = BitmapFactory.decodeFile(s.file.path) ?: continue
                val px = IntArray(bm.width * bm.height); bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
                for (k in px.indices) px[k] = px[k] and 0xFFFFFF
                val img = Rgb(bm.width, bm.height, px); bm.recycle()
                perStrip.add(eng.recognize(img).filter { it.conf >= 0.55f && it.text.count { c -> c.isLetterOrDigit() } >= 1 }.map { Line(it.text, (it.y0 + it.y1) / 2f / img.h) })
            }
            onProgress(Progress("Stitching", strips.size, strips.size))
            val raw = perStrip.sumOf { it.size }
            val cleaned = Chrome.strip(perStrip)
            val sp = Spacing.load(ctx.assets.open("words.txt").use { String(it.readBytes(), Charsets.UTF_8) }.split("\n"))
            val merged = Stitch.merge(cleaned.map { st -> st.map { sp.fix(it.text) } }).map { Fix.lookalikes(it) }
            val ms = System.currentTimeMillis() - t0
            return ReadResult(merged.joinToString("\n"), strips.size, raw, merged.size, ms,
                "detect ${eng.detMs}ms, recognize ${eng.recMs}ms, post ${eng.postMs}ms")
        } finally { eng.close() }
    }
}
