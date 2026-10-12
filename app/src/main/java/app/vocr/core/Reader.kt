package app.vocr.core

import android.content.Context
import android.graphics.BitmapFactory

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
        val perStrip = ArrayList<List<String>>()
        try {
            for ((i, s) in strips.withIndex()) {
                onProgress(Progress("Reading text", i, strips.size))
                val bm = BitmapFactory.decodeFile(s.file.path) ?: continue
                val px = IntArray(bm.width * bm.height); bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
                for (k in px.indices) px[k] = px[k] and 0xFFFFFF
                val img = Rgb(bm.width, bm.height, px); bm.recycle()
                perStrip.add(eng.recognize(img).map { it.text }.filter { it.isNotBlank() })
            }
            onProgress(Progress("Stitching", strips.size, strips.size))
            val merged = Stitch.merge(perStrip)
            val raw = perStrip.sumOf { it.size }
            val ms = System.currentTimeMillis() - t0
            return ReadResult(merged.joinToString("\n"), strips.size, raw, merged.size, ms,
                "detect ${eng.detMs}ms, recognize ${eng.recMs}ms, post ${eng.postMs}ms")
        } finally { eng.close() }
    }
}
