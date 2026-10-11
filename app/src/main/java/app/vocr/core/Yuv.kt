package app.vocr.core

/** Compact planar YUV420 frame copied out of a decoder Image (full-res Y, half-res U and V). */
class YuvFrame(val w: Int, val h: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
    private fun yAt(x: Int, yy: Int) = (y[yy * w + x].toInt() and 0xFF)

    /** Average-pooled grayscale at dw x dh straight from the Y plane (luma needs no RGB conversion). */
    fun gray(dw: Int, dh: Int): Gray {
        val d = FloatArray(dw * dh)
        // sample up to 3x3 points per output cell instead of reading every source pixel
        val xs = Array(dw) { i -> val x0 = i * w / dw; val x1 = maxOf(x0 + 1, (i + 1) * w / dw); IntArray(3) { k -> x0 + (x1 - x0) * (2 * k + 1) / 6 } }
        for (j in 0 until dh) {
            val y0 = j * h / dh; val y1 = maxOf(y0 + 1, (j + 1) * h / dh)
            for (i in 0 until dw) {
                var s = 0
                val xi = xs[i]
                for (k in 0 until 3) { val yy = minOf(h - 1, y0 + (y1 - y0) * (2 * k + 1) / 6); val o = yy * w; for (m in 0 until 3) s += y[o + xi[m]].toInt() and 0xFF }
                d[j * dw + i] = s / 9f
            }
        }
        return Gray(dw, dh, d)
    }

    companion object {
    /** Small grayscale derived from an existing Gray by block averaging. */
    fun shrink(g: Gray, ow: Int, oh: Int): Gray {
        val d = FloatArray(ow * oh)
        for (j in 0 until oh) { val y0 = j * g.h / oh; val y1 = maxOf(y0 + 1, (j + 1) * g.h / oh)
            for (i in 0 until ow) { val x0 = i * g.w / ow; val x1 = maxOf(x0 + 1, (i + 1) * g.w / ow)
                var s = 0f; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) { s += g.d[yy * g.w + xx]; n++ }
                d[j * ow + i] = s / n } }
        return Gray(ow, oh, d)
    }
    }

    /** Nearest-sample ARGB pixels at ow x oh (BT.601 full range). */
    fun argb(ow: Int, oh: Int): IntArray {
        val out = IntArray(ow * oh)
        val cw = w / 2
        for (j in 0 until oh) {
            val sy = j * h / oh
            for (i in 0 until ow) {
                val sx = i * w / ow
                val yy = yAt(sx, sy).toFloat()
                val ci = (sy / 2) * cw + (sx / 2)
                val uu = (u[ci].toInt() and 0xFF) - 128
                val vv = (v[ci].toInt() and 0xFF) - 128
                val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
                val g = (yy - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
                out[j * ow + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}
