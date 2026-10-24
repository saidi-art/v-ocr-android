package app.vocr

import app.vocr.sphere.SphereTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SphereToolsTest {

    @Test fun `a lone tap never captures`() {
        org.junit.Assert.assertFalse(SphereTools.secondTapFires(0L, 1000L, 233L))
    }

    @Test fun `the second tap inside the window captures`() {
        org.junit.Assert.assertTrue(SphereTools.secondTapFires(1000L, 1150L, 233L))
        org.junit.Assert.assertTrue(SphereTools.secondTapFires(1000L, 1233L, 233L))
    }

    @Test fun `a late second tap is a new first tap`() {
        org.junit.Assert.assertFalse(SphereTools.secondTapFires(1000L, 1234L, 233L))
        org.junit.Assert.assertFalse(SphereTools.secondTapFires(1000L, 5000L, 233L))
    }

    @Test fun `active taps do nothing when idle`() {
        org.junit.Assert.assertNull(SphereTools.activeTapAction(secondTap = false, shotActive = false, recActive = false))
        org.junit.Assert.assertNull(SphereTools.activeTapAction(secondTap = true, shotActive = false, recActive = false))
    }

    @Test fun `active first tap is Done and second tap is Cancel for a capture`() {
        assertEquals("app.vocr.sphere.SHOT_FINISH", SphereTools.activeTapAction(secondTap = false, shotActive = true, recActive = false))
        assertEquals("app.vocr.sphere.SHOT_CANCEL", SphereTools.activeTapAction(secondTap = true, shotActive = true, recActive = false))
    }

    @Test fun `active first tap is Done and second tap is Cancel for a recording`() {
        assertEquals("app.vocr.sphere.REC", SphereTools.activeTapAction(secondTap = false, shotActive = false, recActive = true))
        assertEquals("app.vocr.sphere.REC_CANCEL", SphereTools.activeTapAction(secondTap = true, shotActive = false, recActive = true))
    }

    @Test fun `Done is never the same action as Cancel`() {
        assertNotEquals(SphereTools.doneTap(shotActive = true), SphereTools.cancelTap(recActive = false))
        assertNotEquals(SphereTools.doneTap(shotActive = false), SphereTools.cancelTap(recActive = true))
    }
}
