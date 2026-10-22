package app.vocr

import app.vocr.core.OcrEngine
import app.vocr.core.OcrLine
import app.vocr.core.Rgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure pixel tests for the dense-text rescue gate: no models needed, denseSignal is a static pixel pass. */
class DenseGateTest {
    /** 200x100 white image with one "text row" of alternating black/white columns in y0..y1: every column step is an edge. */
    private fun imgWithInkBand(x0: Int, x1: Int, y0: Int, y1: Int): Rgb {
        val img = Rgb(200, 100, IntArray(200 * 100) { 0xFFFFFF })
        for (y in y0 until y1) for (x in x0 until x1) img.px[y * 200 + x] = if ((x + y) % 2 == 0) 0 else 0xFFFFFF
        return img
    }
    private fun box(x0: Int, y0: Int, x1: Int, y1: Int) = OcrLine("text", x0, y0, x1, y1, 0.9f)

    @Test fun inkInsideBoxesStaysQuiet() {
        val img = imgWithInkBand(20, 180, 40, 50)
        val (edgeFrac, outsideFrac) = OcrEngine.denseSignal(img, listOf(box(17, 37, 183, 53)))   // detector boxes are padded; cover the band boundary too
        assertTrue("edgeFrac $edgeFrac", edgeFrac >= 0.005f)
        assertEquals(0f, outsideFrac, 0.001f)
        assertFalse(OcrEngine.denseGateFires(edgeFrac, outsideFrac))
    }

    @Test fun inkOutsideBoxesFires() {
        val img = imgWithInkBand(20, 180, 40, 50)
        val (edgeFrac, outsideFrac) = OcrEngine.denseSignal(img, listOf(box(150, 80, 199, 99)))   // box nowhere near the ink
        assertTrue("edgeFrac $edgeFrac", edgeFrac >= 0.005f)
        assertEquals(1f, outsideFrac, 0.001f)
        assertTrue(OcrEngine.denseGateFires(edgeFrac, outsideFrac))
    }

    @Test fun blankImageStaysQuiet() {
        val (edgeFrac, outsideFrac) = OcrEngine.denseSignal(Rgb(200, 100, IntArray(200 * 100) { 0xFFFFFF }), emptyList())
        assertEquals(0f, edgeFrac, 0.0001f)
        assertFalse(OcrEngine.denseGateFires(edgeFrac, outsideFrac))
    }

    @Test fun gateThresholds() {
        assertFalse(OcrEngine.denseGateFires(0.004f, 0.9f))    // not enough ink to matter
        assertFalse(OcrEngine.denseGateFires(0.01f, 0.49f))    // ink mostly read
        assertTrue(OcrEngine.denseGateFires(0.005f, 0.50f))    // boundary fires
        assertTrue(OcrEngine.denseGateFires(0.023f, 0.878f))   // the BoursoBank frame's measured values
        assertFalse(OcrEngine.denseGateFires(0.032f, 0.071f))  // a healthy dense page (intlieneess) stays quiet
    }
}
