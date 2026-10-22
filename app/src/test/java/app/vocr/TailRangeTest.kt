package app.vocr

import app.vocr.capture.LongShotSession
import org.junit.Assert.*
import org.junit.Test

class TailRangeTest {
    @Test fun noLearnedBarKeepsEverything() {
        assertNull(LongShotSession.tailRange(2340, 600, 0))
        assertNull(LongShotSession.tailRange(2340, 600, 700))   // over the h/4 sanity cap
    }
    @Test fun learnedBarCropsTheTail() {
        val r = LongShotSession.tailRange(2340, 607, 487)!!
        assertEquals(2340 - 607, r[0])
        assertEquals(2340 - 487, r[1])
    }
    @Test fun barCoveringTheWholeTailLeavesNothing() {
        val r = LongShotSession.tailRange(2340, 500, 500)!!
        assertEquals(r[0], r[1])
    }
}
