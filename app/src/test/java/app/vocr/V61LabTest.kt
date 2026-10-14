package app.vocr

import app.vocr.core.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class V61LabTest {
    val W = 360; val H = 780
    @Test fun lab() {
        val f = File("/tmp/v61.raw"); if (!f.exists()) return
        val raf = RandomAccessFile(f, "r"); val n = (f.length() / (W * H)).toInt(); val buf = ByteArray(W * H)
        fun gray(k: Int): Gray { raf.seek(k.toLong() * W * H); raf.readFully(buf); val d = FloatArray(W * H); for (i in 0 until W * H) d[i] = (buf[i].toInt() and 255).toFloat(); return Gray(W, H, d) }
        fun small(g: Gray): Gray { val o = FloatArray(1024); for (j in 0 until 32) for (i in 0 until 32) { var s = 0f; var c = 0
            for (y in j * g.h / 32 until maxOf(j * g.h / 32 + 1, (j + 1) * g.h / 32)) for (x in i * g.w / 32 until maxOf(i * g.w / 32 + 1, (i + 1) * g.w / 32)) { s += g.d[y * g.w + x]; c++ }
            o[j * 32 + i] = s / c }; return Gray(32, 32, o) }
        val modes = (System.getProperty("vmodes") ?: "fixed3,fixed10,fixed20,fixed30,adaptive").split(",")
        for (mode in modes) {
            val sb = StripBuilder(overlapPx = 18)
            val fps = 30.0; var t = 0.0; var read = 0; var firstFail = -1.0; var strips = 0; var full = 0; var crop = 0; var skip = 0; var area = 0.0
            val base = if (mode.startsWith("fixed")) mode.removePrefix("fixed").toDouble() else 3.0
            val rate = RateCtl(base); var cur = base
            val fails = ArrayList<Double>(); val log = StringBuilder(); var nextT = 0.0; var rateSum = 0.0
            for (k in 0 until n) {
                val tt = k / fps; if (tt < nextT) continue
                val g = gray(k); read++
                val dec = sb.push(Hash.phash(small(g)), g)
                when (dec) { is Decision.Skip -> skip++; is Decision.Full -> { full++; strips++; area += H }; is Decision.Crop -> { crop++; strips++; area += dec.bottom - dec.top } }
                if (sb.lastFailed && firstFail < 0) firstFail = tt
                if (sb.lastFailed) fails.add(tt)
                if (log.length < 600 && tt < 40) log.append("%.2f:%s ".format(tt, when (dec) { is Decision.Skip -> "s"; is Decision.Full -> if (sb.lastFailed) "F!" else "F"; is Decision.Crop -> "C" + (dec.bottom - dec.top) }))
                if (mode == "adaptive") cur = rate.update(sb)
                rateSum += cur
                nextT = tt + 1.0 / cur
            }
            sb.flush()
            println("V61 $mode read=$read strips=$strips full=$full crop=$crop skip=$skip scrollMatches=${sb.scrollMatches} bandMatches=${sb.bandMatches} firstFail=$firstFail fullFrameEquiv=${"%.0f".format(area / H)} meanRate=${"%.1f".format(rateSum / maxOf(1, read))}")
            println("V61 $mode fails/10s: " + (0..22).joinToString(" ") { b -> fails.count { it >= b * 10 && it < b * 10 + 10 }.toString() })
            println("V61 $mode failtimes: " + fails.take(60).joinToString(" ") { "%.2f".format(it) })
        }
    }
}
