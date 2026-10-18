package app.vocr

import app.vocr.capture.SwipeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeZoneTest {
    private val h = 2340f
    private val w = 1080f

    private fun inZone(y: Float, bottom: Float) = y >= SwipeZone.Y_MIN - 1e-4f && y <= bottom + 1e-4f

    @Test fun zoneMatchesProposalOn1080x2340() {
        assertEquals(150f, SwipeZone.Y_MIN * h, 0.5f)
        assertEquals(1700f, SwipeZone.Y_MAX * h, 0.5f)
        assertEquals(80f, SwipeZone.X_MIN * w, 0.5f)
        assertEquals(1000f, SwipeZone.X_MAX * w, 0.5f)
        assertEquals(600f, SwipeZone.LEN_MIN * h, 0.5f)
        assertEquals(1500f, SwipeZone.LEN_MAX * h, 0.5f)
    }

    @Test fun neverStartsInTheInputBoxOrBars() {
        val bottom = SwipeZone.bottom(h, 135)
        for (mid in listOf(0.0f, 0.2f, 0.49f, 0.8f, 1.0f)) for (want in listOf(0.05f, 0.25f, 0.5f, 0.9f, 1.5f)) {
            val (y0, y1) = SwipeZone.forward(mid, want, bottom)
            assertTrue("start $y0", inZone(y0, bottom)); assertTrue("end $y1", inZone(y1, bottom))
            assertTrue(y0 > y1)
            val len = (y0 - y1) * h
            assertTrue("len $len", len >= 599f && len <= 1501f)
            assertTrue("start px ${y0 * h}", y0 * h <= 1700.5f)   // the chat box starts at 1776 px
            val (b0, b1) = SwipeZone.back(want, bottom)
            assertTrue(inZone(b0, bottom)); assertTrue(inZone(b1, bottom)); assertTrue(b0 < b1)
        }
    }

    @Test fun defaultsAreInsideTheZone() {
        // first probe 0.72 -> 0.46, retry from forward(0.51, 0.42)
        val bottom = SwipeZone.bottom(h, 135)
        assertTrue(0.72f <= bottom && 0.46f >= SwipeZone.Y_MIN)
        assertEquals((0.72f - 0.46f) * h, 608.4f, 1f)
        val (r0, r1) = SwipeZone.forward(0.51f, 0.42f, bottom)
        assertEquals(0.72f, r0, 0.005f); assertEquals(0.30f, r1, 0.005f)
    }

    @Test fun calibratedExampleFromTheMap() {
        // ratio 1.0, header 97, footer 135: the old code started at 1731 px (inside the 45 px margin of the box); now it stays at or above 1700 px
        val band = h - 97 - 135; val dist = band * 0.55f; val mid = 97 + band / 2f
        val (y0, y1) = SwipeZone.forward(mid / h, dist / h, SwipeZone.bottom(h, 135))
        assertTrue(y0 * h <= 1700.5f); assertEquals(dist, (y0 - y1) * h, 1f)
    }

    @Test fun tallFooterLowersTheZoneAndXIsClamped() {
        val bottom = SwipeZone.bottom(h, 600)
        assertTrue(bottom * h <= 1740f - 46f)
        val (y0, _) = SwipeZone.forward(0.9f, 0.5f, bottom); assertTrue(y0 <= bottom + 1e-4f)
        assertEquals(SwipeZone.X_MIN, SwipeZone.x(0.01f), 1e-6f); assertEquals(SwipeZone.X_MAX, SwipeZone.x(0.99f), 1e-6f)
        assertEquals(0.35f, SwipeZone.x(0.35f), 1e-6f)
    }
}
