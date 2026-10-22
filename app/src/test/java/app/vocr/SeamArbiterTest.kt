package app.vocr

import app.vocr.capture.SeamArbiter
import org.junit.Assert.*
import org.junit.Test

class SeamArbiterTest {
    @Test fun twoPixelReadsBeatThePhone() {
        // the Claude defect: phone read 587, pixels insisted ~750 twice
        val v = SeamArbiter.decide(587, 754, 761)
        assertEquals(761, v.dy)
        assertTrue(v.who.startsWith("pixels"))
    }
    @Test fun agreementAfterSettlingUsesPixels() {
        val v = SeamArbiter.decide(600, 750, 612)   // second read within 10% of the phone
        assertEquals(612, v.dy)
    }
    @Test fun unstablePixelsLeaveThePhoneInCharge() {
        val v = SeamArbiter.decide(587, 754, 300)   // pixels disagree with themselves
        assertEquals(587, v.dy)
        assertTrue(v.who.startsWith("the phone"))
    }
    @Test fun failedRecaptureLeavesThePhoneInCharge() {
        val v = SeamArbiter.decide(587, 754, -1)
        assertEquals(587, v.dy)
    }
    @Test fun toleranceIsAtLeast24px() {
        assertEquals(24, SeamArbiter.tol(100))
        assertEquals(75, SeamArbiter.tol(754))
    }
}
