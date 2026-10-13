package app.vocr

import app.vocr.core.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Glyph-size floor lab: reads synthetic pages from /tmp/floor/p_*.png and writes o_*.txt plus timings. */
class FloorLabTest {
    @Test fun floor() {
        val dir = File("src/main/assets/ocr"); val inDir = File("/tmp/floor")
        assumeTrue(File(dir, "det.onnx").exists() && inDir.exists() && System.getenv("FLOOR") != null)
        val eng = OcrEngine(File(dir, "det.onnx").readBytes(), File(dir, "rec.onnx").readBytes(), File(dir, "keys.txt").readText().split("\n"))
        System.getenv("FLOORSIDE")?.let { eng.maxSide = it.toInt() }
        if (System.getenv("FLOORNOADAPT") != null) eng.adaptive = false
        val only = System.getenv("FLOORONLY")?.split(",")
        for (f in inDir.listFiles()!!.filter { it.name.startsWith("p_") && it.name.endsWith(".rgb") }.filter { only == null || only.any { k -> it.name.startsWith("p_${k}_") } }.sortedBy { it.name }) {
            val w = 720; val h = 1560; val raw = f.readBytes()
            val px = IntArray(w * h) { ((raw[it * 3].toInt() and 255) shl 16) or ((raw[it * 3 + 1].toInt() and 255) shl 8) or (raw[it * 3 + 2].toInt() and 255) }
            val t0 = System.currentTimeMillis(); val ls = eng.recognize(Rgb(w, h, px)); val ms = System.currentTimeMillis() - t0
            File(inDir, f.name.replace("p_", System.getenv("FLOORPFX") ?: "o_").replace(".rgb", ".txt")).writeText(ls.joinToString("\n") { it.text })
            println("FLOOR ${f.name} ms=$ms lines=${ls.size} hi=${eng.hiCount} med=${eng.lastMedH}")
        }
    }
}
