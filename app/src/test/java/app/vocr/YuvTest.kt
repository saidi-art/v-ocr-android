package app.vocr

import app.vocr.core.YuvFrame
import org.junit.Assert.*
import org.junit.Test

class YuvTest {
    // 8x8 frame: left half luma 50, right half luma 200, neutral chroma
    private fun frame(): YuvFrame {
        val y = ByteArray(64) { i -> if (i % 8 < 4) 50 else 200.toByte() }
        return YuvFrame(8, 8, y, ByteArray(16) { 128.toByte() }, ByteArray(16) { 128.toByte() })
    }
    @Test fun grayPoolsLuma() {
        val g = frame().gray(2, 2)
        assertEquals(50f, g.d[0], 0.01f); assertEquals(200f, g.d[1], 0.01f)
        assertEquals(50f, g.d[2], 0.01f); assertEquals(200f, g.d[3], 0.01f)
    }
    @Test fun argbNeutralChromaIsGrey() {
        val p = frame().argb(8, 8)
        assertEquals(0xFF323232.toInt(), p[0]); assertEquals(0xFFC8C8C8.toInt(), p[7])
    }
    @Test fun argbRedFromChroma() {
        val u = ByteArray(16) { 90.toByte() }; val v = ByteArray(16) { 240.toByte() } // red-ish
        val p = YuvFrame(8, 8, ByteArray(64) { 80 }, u, v).argb(8, 8)[0]
        val r = (p shr 16) and 0xFF; val b = p and 0xFF
        assertTrue("r=$r b=$b", r > 200 && b < 80)
    }
}
