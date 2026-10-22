package app.vocr

import app.vocr.core.ScrollTrace
import org.junit.Assert.*
import org.junit.Test

class ScrollTraceTest {
    private fun traced(): ScrollTrace {
        val tr = ScrollTrace(2340)
        // events from t=1000..5000, page moves 100px/s
        for (t in 1000L..5000L step 100L) { tr.event(t); tr.add(t, (t - 1000) .toInt()) }
        return tr
    }
    @Test fun distanceInsideOneSegment() {
        val tr = traced()
        assertEquals(1000, tr.pxBetween(2000, 3000))
        assertEquals(0, tr.pxBetween(2500, 2500))
    }
    @Test fun doubtWithoutEnoughEvents() {
        val tr = ScrollTrace(2340)
        for (t in 1000L..3000L step 100L) tr.add(t, (t - 1000).toInt())   // snapshots, no events
        assertEquals(-1, tr.pxBetween(1500, 2500))
        tr.event(1000); tr.event(2000)   // still below MIN_EVENTS
        assertEquals(-1, tr.pxBetween(1500, 2500))
    }
    @Test fun doubtOutsideTheEventWindow() {
        val tr = traced()
        assertEquals(-1, tr.pxBetween(0, 500))            // before any event flow
        assertEquals(-1, tr.pxBetween(2000, 20000))       // long after the last event
    }
    @Test fun reanchorBreaksTheLine() {
        val tr = traced()
        tr.reanchor(5000)
        for (t in 5100L..6000L step 100L) { tr.event(t); tr.add(t, (t - 5100).toInt()) }   // re-based from 0
        assertEquals(-1, tr.pxBetween(4000, 5500))        // across the break: unknown
        assertEquals(400, tr.pxBetween(5200, 5600))       // inside the new segment: fine
    }
    @Test fun serializeRoundTrip() {
        val tr = traced()
        tr.reanchor(5000)
        for (t in 5100L..6000L step 100L) { tr.event(t); tr.add(t, (t - 5100).toInt()) }
        val back = ScrollTrace.parse(tr.serialize())!!
        assertEquals(2340, back.screenH)
        assertEquals(tr.events(), back.events())
        assertEquals(1000, back.pxBetween(2000, 3000))
        assertEquals(-1, back.pxBetween(4000, 5500))
        assertEquals(400, back.pxBetween(5200, 5600))
    }
    @Test fun interpolateBetweenSnapshots() {
        val tr = traced()
        // polls every 100ms; a frame at 2050ms takes the 2000ms snapshot
        assertEquals(1000, tr.pxBetween(2050, 3050))
    }
}
