package app.vocr

import app.vocr.capture.SphereShade
import org.junit.Assert.*
import org.junit.Test

class SphereShadeTest {
    @Test fun threeSixtyFiveDistinctBodyColours() {
        assertEquals(365, (1..365).map { SphereShade.big(it)[1] }.toSet().size)
    }
    @Test fun smallOnesAreTheComplement() {
        // the complementary body hue is 180 degrees away: its red/green/blue order differs from the big one's for most days
        assertNotEquals(SphereShade.big(289)[1], SphereShade.small(289)[1])
    }
    @Test fun deterministicAndClamped() {
        assertArrayEquals(SphereShade.big(100), SphereShade.big(100))
        assertArrayEquals(SphereShade.big(366), SphereShade.big(365))
    }
    @Test fun day289IsGreen() {
        val c = SphereShade.big(289)[1]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        assertTrue("r=$r g=$g b=$b", g > r && g > b)
    }
}
