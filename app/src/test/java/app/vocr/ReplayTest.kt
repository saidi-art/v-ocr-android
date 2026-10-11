package app.vocr

import app.vocr.core.*
import org.junit.Test
import java.io.File

class ReplayTest {
    @Test fun replay() {
        val raw = File("/tmp/rf.raw"); if (!raw.exists()) return
        val bytes = raw.readBytes(); val W = 360; val H = 780; val n = bytes.size / (W * H)
        val files = (0 until n).toList()
        fun gray(k: Int): Gray { val d = FloatArray(W * H); val o = k * W * H; for (i in 0 until W * H) d[i] = (bytes[o + i].toInt() and 255).toFloat(); return Gray(W, H, d) }
        fun small(g: Gray): Gray { val o = FloatArray(1024); for (j in 0 until 32) for (i in 0 until 32) { var s = 0f; var n = 0
            for (y in j * g.h / 32 until maxOf(j * g.h / 32 + 1, (j + 1) * g.h / 32)) for (x in i * g.w / 32 until maxOf(i * g.w / 32 + 1, (i + 1) * g.w / 32)) { s += g.d[y * g.w + x]; n++ }
            o[j * 32 + i] = s / n }; return Gray(32, 32, o) }
        for (mode in listOf("fixed3fps", "adaptive")) {
            val sb = StripBuilder(overlapPx = 18)
            var idx = 0; var read = 0; val t0 = System.nanoTime(); val log = StringBuilder()
            while (idx < n) {
                val g = gray(idx); read++
                val dec = sb.push(Hash.phash(small(g)), g)
                log.append(" ").append(idx).append(":").append(when (dec) { is Decision.Skip -> "s"; is Decision.Full -> if (sb.lastFailed) "F!" else "F"; is Decision.Crop -> "C" + (dec.bottom - dec.top) })
                idx += if (mode == "adaptive" && sb.lastFailed) 1 else 4
            }
            val fin = sb.flush()
            println("REPLAY $mode frames_read=$read dup=${sb.dup} full=${sb.full} cropped=${sb.cropped} flush=$fin ms=${(System.nanoTime() - t0) / 1_000_000}")
            println("REPLAY $mode seq:$log")
        }
    }
}
