package app.vocr

import app.vocr.capture.ChromeMap
import app.vocr.core.Tokens
import org.junit.Assert.*
import org.junit.Test

class TokensTest {
    @Test fun malformedDetection() {
        assertFalse(Tokens.malformed("name@gmail.com"))
        assertTrue(Tokens.malformed("name@.gmail.com"))
        assertTrue(Tokens.malformed("name@gmail.c0m"))
        assertTrue(Tokens.malformed("@gmail.com"))
        assertFalse(Tokens.malformed("a@mail2u.com"))   // digits can be real
        assertTrue(Tokens.malformed("0ct")); assertTrue(Tokens.malformed("(0ct"))
        assertFalse(Tokens.malformed("Oct")); assertFalse(Tokens.malformed("October"))
        assertTrue(Tokens.malformed("github,com")); assertFalse(Tokens.malformed("github.com"))
        assertFalse(Tokens.malformed("brie..."))   // an ellipsis is prose, not a broken link
        assertFalse(Tokens.malformed("10:45")); assertFalse(Tokens.malformed("AF1380")); assertFalse(Tokens.malformed("2,213"))
        assertFalse(Tokens.malformed("hi")); assertFalse(Tokens.malformed("@"))
    }

    @Test fun fixLineRepairsMonthsOnly() {
        assertEquals("(Oct 25,2026)", Tokens.fixLine("(0ct 25,2026)"))
        assertEquals("due Sep 1", Tokens.fixLine("due 5ep 1"))
        assertEquals("Oct", Tokens.fixLine("Oct"))
        assertEquals("release 1O stays", Tokens.fixLine("release 1O stays"))
        // open-class tokens are never guessed: only pixels (repairLine) may change them
        assertEquals("mail name@.gmail.com now", Tokens.fixLine("mail name@.gmail.com now"))
        assertEquals("a@gmail.c0m", Tokens.fixLine("a@gmail.c0m"))
        assertEquals("github,com", Tokens.fixLine("github,com"))
    }

    @Test fun chooseVoteNeedsTwoAgreeingReads() {
        val old = listOf("send", "name@.gmail.com", "today")
        // two of three phases agree on the valid reading: swap
        assertEquals(listOf("send", "name@gmail.com", "today"), Tokens.chooseVote(old, listOf(
            listOf("send", "name@gmail.com", "today"),
            listOf("send", "name@gmail.com", "today"),
            listOf("send", "name@gmail.xom", "today"))))
        // only one valid reading: no swap
        assertEquals(old, Tokens.chooseVote(old, listOf(
            listOf("send", "name@gmail.com", "today"),
            listOf("send", "name@gmail.xom", "today"))))
        // all three disagree: no swap
        assertEquals(old, Tokens.chooseVote(old, listOf(
            listOf("send", "name@gmail.com", "today"),
            listOf("send", "name@qmail.com", "today"),
            listOf("send", "name@gmail.xom", "today"))))
        // a winning replacement must keep the edge punctuation
        assertEquals(listOf("(0ct"), Tokens.chooseVote(listOf("(0ct"), listOf(listOf("0ct"), listOf("0ct"), listOf("(0ct"))))
        // reads with a different word count are ignored
        assertEquals(old, Tokens.chooseVote(old, listOf(
            listOf("send", "name@gmail.com", "to", "today"),
            listOf("send", "name@gmail.com", "to", "today"))))
        // clean lines pass through untouched
        assertEquals(listOf("all", "good"), Tokens.chooseVote(listOf("all", "good"), listOf(listOf("all", "good"), listOf("all", "good"))))
    }
}

class ChromeMapTest {
    @Test fun firstPairRecordsHeaderBandOnly() {
        val out = mutableListOf<IntArray>()
        ChromeMap.add(out, 0, 0, 1960, 130, 260, 2340)   // first capture written 0..bottom-FADE
        assertEquals(1, out.size); assertArrayEquals(intArrayOf(0, 130), out[0])
    }
    @Test fun finalFrameRecordsFooterBand() {
        val out = mutableListOf<IntArray>()
        ChromeMap.add(out, 6000, 2000, 2340, 0, 260, 2340)   // finish(): last capture rows 2000..2340 at stitched row 6000
        assertEquals(1, out.size); assertArrayEquals(intArrayOf(6080, 6340), out[0])
    }
    @Test fun midRunAppendInsideBandsRecordsNothing() {
        val out = mutableListOf<IntArray>()
        ChromeMap.add(out, 1000, 500, 1800, 130, 260, 2340)
        assertTrue(out.isEmpty())
    }
    @Test fun sliceReachingIntoBothBands() {
        val out = mutableListOf<IntArray>()
        ChromeMap.add(out, 0, 0, 2340, 130, 260, 2340)   // a whole frame written whole (should not happen, but stays correct)
        assertEquals(2, out.size); assertArrayEquals(intArrayOf(0, 130), out[0]); assertArrayEquals(intArrayOf(2080, 2340), out[1])
    }
    @Test fun systemBarsOfOneFrame() {
        val r = ChromeMap.systemBars(2340, 97, 135)   // the S24 screenshot: status bar 97, navigation bar 135
        assertEquals(2, r.size); assertArrayEquals(intArrayOf(0, 97), r[0]); assertArrayEquals(intArrayOf(2205, 2340), r[1])
        assertTrue(ChromeMap.contains(r, 60)); assertFalse(ChromeMap.contains(r, 97))
        assertTrue(ChromeMap.contains(r, 2205)); assertTrue(ChromeMap.contains(r, 2339)); assertFalse(ChromeMap.contains(r, 2204))
    }
    @Test fun systemBarsSkipsMissingAndInsane() {
        assertTrue(ChromeMap.systemBars(2340, 0, 0).isEmpty())   // no system bar heights known: nothing trimmed
        assertTrue(ChromeMap.systemBars(2340, 700, 0).isEmpty())   // taller than a quarter of the screen is not a bar
        val r = ChromeMap.systemBars(2340, 0, 135)
        assertEquals(1, r.size); assertArrayEquals(intArrayOf(2205, 2340), r[0])
    }
    @Test fun containsFindsRanges() {
        val r = listOf(intArrayOf(0, 130), intArrayOf(6080, 6340))
        assertTrue(ChromeMap.contains(r, 0)); assertTrue(ChromeMap.contains(r, 129)); assertTrue(ChromeMap.contains(r, 6339))
        assertFalse(ChromeMap.contains(r, 130)); assertFalse(ChromeMap.contains(r, 6079)); assertFalse(ChromeMap.contains(r, 6340))
    }
}
