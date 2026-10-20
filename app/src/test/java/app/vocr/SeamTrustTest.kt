package app.vocr

import app.vocr.capture.Mark
import app.vocr.capture.SeamTrust
import app.vocr.core.Line
import org.junit.Assert.*
import org.junit.Test

class SeamTrustTest {
    @Test fun logCaseAccepts() {
        // the 09:22 Claude chat: four recaptures all measured dy=348 at ratio 1.00
        assertEquals(348, SeamTrust.positionOnlyDy(listOf(348, 348, 348, 348), 1.00f))
        assertEquals(348, SeamTrust.positionOnlyDy(listOf(348, 348, 348), 1.00f))
    }
    @Test fun agreementWithinEightPx() {
        assertEquals(350, SeamTrust.positionOnlyDy(listOf(348, 350, 356), 1.00f))   // span 8: median
        assertEquals(-1, SeamTrust.positionOnlyDy(listOf(348, 348, 359), 1.00f))   // span 11: still the drag-back recovery
    }
    @Test fun calibrationGate() {
        assertEquals(-1, SeamTrust.positionOnlyDy(listOf(348, 348, 348), 1.06f))
        assertEquals(-1, SeamTrust.positionOnlyDy(listOf(348, 348, 348), 0.94f))
        assertEquals(348, SeamTrust.positionOnlyDy(listOf(348, 348, 348), 0.95f))
        assertEquals(348, SeamTrust.positionOnlyDy(listOf(348, 348, 348), 1.05f))
    }
    @Test fun needsThreeRecaptures() {
        assertEquals(-1, SeamTrust.positionOnlyDy(listOf(348, 348), 1.00f))
        assertEquals(-1, SeamTrust.positionOnlyDy(emptyList(), 1.00f))
    }
}

class MarkInjectTest {
    private fun lines(vararg ys: Float) = ys.map { Line("line at $it", it) }
    @Test fun markerLandsByRowPosition() {
        val out = Mark.inject(lines(0.1f, 0.5f, 0.9f), listOf(Mark(4000, "[ScrollRSI: possible gap - up to ~582 px of content may be missing here]")), 3200, 3200)
        assertEquals(4, out.size)
        assertEquals("line at 0.1", out[0].text)
        assertTrue(out[1].text.startsWith("[ScrollRSI: possible gap"))   // row 4000 = frac 0.25, between 0.1 and 0.5
        assertEquals("line at 0.5", out[2].text)
    }
    @Test fun markersOutsideTheStripStayOut() {
        val l = lines(0.1f, 0.9f)
        assertSame(l, Mark.inject(l, listOf(Mark(100, "x"), Mark(99999, "y")), 3200, 3200))
    }
    @Test fun twoMarkersKeepRowOrder() {
        val out = Mark.inject(lines(0.5f), listOf(Mark(6000, "second"), Mark(3300, "first")), 3200, 3200)
        assertEquals(listOf("first", "line at 0.5", "second"), out.map { it.text })
    }
}
