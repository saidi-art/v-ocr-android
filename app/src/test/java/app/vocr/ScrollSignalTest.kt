package app.vocr

import app.vocr.capture.ScrollSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone-measured scroll distance: delta apps, position apps, re-anchors, noise (device evidence Oct 2026). */
class ScrollSignalTest {

    @Test fun `delta app sums absolute deltas - Grok shape`() {
        val s = ScrollSignal(); s.arm(2340)
        // Grok reports negative deltas for a scroll down; y and maxY come along too
        var y = 5085
        for (d in listOf(-161, -161, -161, -162, -161, -131, -238, -192, -104, -56, -38, -20, -8, -2)) { y += d; s.onScroll("ai.x.grok.bot", d, y) }
        val r = s.read()
        assertEquals(ScrollSignal.Kind.DELTA, r.kind)
        assertTrue(r.reliable)
        assertEquals(1595, r.px)
    }

    @Test fun `delta app positive sign - Gemini shape, y always 0`() {
        val s = ScrollSignal(); s.arm(2340)
        for (d in listOf(147, 159, 152, 150, 156, 153, 43)) s.onScroll("com.google.android.googlequicksearchbox", d, 0)
        val r = s.read()
        assertTrue(r.reliable)
        assertEquals(960, r.px)
    }

    @Test fun `position app sums position steps - Claude shape`() {
        val s = ScrollSignal(); s.arm(2340)
        var y = 789
        s.onScroll("com.anthropic.claude", -1, y)
        for (step in listOf(107, 162, 107, 322, 108, 131)) { y += step; s.onScroll("com.anthropic.claude", -1, y) }
        val r = s.read()
        assertEquals(ScrollSignal.Kind.POSITION, r.kind)
        assertTrue(r.reliable)
        assertEquals(937, r.px)
    }

    @Test fun `a position jump over one screen is a re-anchor - reading unreliable`() {
        val s = ScrollSignal(); s.arm(2340)
        s.onScroll("com.anthropic.claude", -1, 4652)
        s.onScroll("com.anthropic.claude", -1, 4783)
        s.onScroll("com.anthropic.claude", -1, 1033)   // 3750 px in one 100 ms step: list re-anchor, not scrolling
        s.onScroll("com.anthropic.claude", -1, 1159)
        val r = s.read()
        assertFalse(r.reliable)
        assertEquals(-1, r.px)
    }

    @Test fun `page pinned at its end reads near zero - Grok top`() {
        val s = ScrollSignal(); s.arm(2340)
        s.onScroll("ai.x.grok.bot", -152, 4)
        s.onScroll("ai.x.grok.bot", -4, 0)
        val r = s.read()
        assertTrue(r.reliable)
        assertEquals(156, r.px)
    }

    @Test fun `events from a second app are ignored`() {
        val s = ScrollSignal(); s.arm(2340)
        s.onScroll("com.anthropic.claude", -1, 100)
        s.onScroll("com.sec.android.app.launcher", -443, 0)   // recents noise while switching
        s.onScroll("com.anthropic.claude", -1, 350)
        val r = s.read()
        assertEquals(2, r.events)
        assertEquals(250, r.px)
    }

    @Test fun `no events - nothing usable`() {
        val s = ScrollSignal(); s.arm(2340)
        val r = s.read()
        assertEquals(ScrollSignal.Kind.NONE, r.kind)
        assertEquals(-1, r.px)
        assertFalse(r.reliable)
    }

    @Test fun `rearm clears the previous swipe`() {
        val s = ScrollSignal(); s.arm(2340)
        s.onScroll("a.b", 500, 0)
        s.arm(2340)
        assertEquals(0, s.read().events)
    }
}
