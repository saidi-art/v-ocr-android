package app.vocr

import app.vocr.core.DirectText
import org.junit.Assert.*
import org.junit.Test

class DirectTextTest {
    @Test fun provenOverlapMergesCleanly() {
        val a = listOf("one", "two", "three", "four")
        val b = listOf("three", "four", "five", "six")
        assertEquals(listOf("one", "two", "three", "four", "five", "six"), DirectText.merge(listOf(a, b)))
    }
    @Test fun unprovenJoinIsMarkedNotHidden() {
        val a = listOf("alpha", "beta")
        val b = listOf("gamma", "delta")
        val m = DirectText.merge(listOf(a, b))
        assertTrue(m.any { it.contains("join not proven") })
        assertEquals(listOf("alpha", "beta"), m.subList(0, 2))
        assertTrue(m.indexOf("gamma") > 0)
    }
    @Test fun identicalFramesCollapseToOne() {
        val a = listOf("same", "screen")
        assertEquals(a, DirectText.merge(listOf(a, a)))
    }
    @Test fun blankAndEmptyLinesAreDropped() {
        assertEquals(listOf("a", "b"), DirectText.merge(listOf(listOf(" a ", "", "b"), listOf("b", "  "))))
    }
    @Test fun noFramesGivesNoText() {
        assertTrue(DirectText.merge(emptyList()).isEmpty())
    }
    @Test fun overlapNeverExceedsTheShorterSide() {
        assertEquals(0, DirectText.overlap(listOf("x"), listOf("y", "x")))
        assertEquals(1, DirectText.overlap(listOf("x", "y"), listOf("y")))
    }
}
