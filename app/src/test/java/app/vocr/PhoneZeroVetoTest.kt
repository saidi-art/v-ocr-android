package app.vocr

import app.vocr.capture.LongShotSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone-zero veto (Instinct chat evidence, Oct 23 2026): a ~0px phone reading ends the capture only when the images also show no movement. */
class PhoneZeroVetoTest {

    @Test fun `phone zero plus pixel movement is NOT the end - Instinct chat shape`() {
        // Instinct chat: DELTA events sum to 0 while the images move (dy=618, 635, 103 seen on the S24)
        assertFalse(LongShotSession.zeroReadingMeansEnd(0, 618))
        assertFalse(LongShotSession.zeroReadingMeansEnd(0, 103))
        assertFalse(LongShotSession.zeroReadingMeansEnd(5, 12))
    }

    @Test fun `phone zero plus no pixel movement IS the end`() {
        assertTrue(LongShotSession.zeroReadingMeansEnd(0, 0))
        assertTrue(LongShotSession.zeroReadingMeansEnd(7, 11))
        assertTrue(LongShotSession.zeroReadingMeansEnd(11, 3))
    }

    @Test fun `a real phone reading above the noise floor never triggers the veto path`() {
        assertFalse(LongShotSession.zeroReadingMeansEnd(12, 0))
        assertFalse(LongShotSession.zeroReadingMeansEnd(618, 618))
        assertFalse(LongShotSession.zeroReadingMeansEnd(-1, 0))   // no usable reading: not this rule
    }
}
