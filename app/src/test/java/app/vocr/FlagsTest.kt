package app.vocr

import app.vocr.core.Flags
import org.junit.Assert.*
import org.junit.Test

class FlagsTest {

    @Test fun currencyLetterAmountAnchorsGroupedAmounts() {
        // The real shopper-capture pattern: 'E200' (a euro sign read as E) anchors the page as money talk,
        // then every thousands-grouped amount that lost its yen sign gets flagged.
        val lines = listOf(
            "I need you to scout Amazon Japan",
            "for a trending gift E200 budget. I",
            "was considering an Amazon gift",
            "1 .The camera was 30,394 plus",
            "600 shipping, with 500+",
            "bought last month and a 4.6/5",
            "rating from 155 reviews. BCN",
            "2.Add this 10-shot film pack for",
            "1,760. Together that's 32,754,",
            "about 185 before any card",
            "16,400 and had 50+ bought"
        )
        val out = Flags.currency(lines)
        val marks = out.filter { it.startsWith("[ScrollRSI:") }
        assertEquals(4, marks.size)   // E200, 30,394, 1,760 + 32,754 on one line, 16,400
        assertTrue(marks.any { it.contains("'E200'") })
        assertTrue(marks.any { it.contains("'30,394'") })
        assertTrue(marks.any { it.contains("'1,760'") && it.contains("'32,754'") })
        assertTrue(marks.any { it.contains("'16,400'") })
        // clean lines survive untouched and in order
        assertEquals(lines, out.filter { !it.startsWith("[ScrollRSI:") })
        // markers land directly above the line they describe
        val i = out.indexOfFirst { it.contains("'30,394'") }
        assertEquals("1 .The camera was 30,394 plus", out[i + 1])
    }

    @Test fun currencyStaysQuietWithoutAnAnchor() {
        val lines = listOf(
            "The role is remote, a 90-day",
            "contractor role, starting around 1",
            "November. It includes your 90-day targets",
            "and asks for at least 5 years of",
            "experience.",
            "2,213starstody"   // a merged token, not an amount
        )
        assertEquals(lines, Flags.currency(lines))
    }

    @Test fun currencyRespectsCodesAndVersions() {
        // Currency codes carry the meaning themselves; version strings are not amounts.
        val lines = listOf(
            "Google Ads gets EUR 400 a",
            "month. The price is USD 2.99.",
            "under EUR 100 within an agreed",
            "build 0.12.7-t1 is live",
            "paid ¥5,000 upfront"   // a surviving symbol anchors the page; the code-carrying amounts above must still stay quiet
        )
        val out = Flags.currency(lines)
        val marks = out.filter { it.startsWith("[ScrollRSI:") }
        assertTrue(marks.none { it.contains("2.99") || it.contains("0.12") || it.contains("400") || it.contains("100") })
    }

    @Test fun currencyLetterAmountNeedsTwoDigitsAndCleanEdges() {
        // S24-style model numbers stay quiet; a letter alone is not a misread sign.
        assertEquals(listOf("the S24 Ultra and Y2K style"), Flags.currency(listOf("the S24 Ultra and Y2K style")))
        val out = Flags.currency(listOf("budget Y1500 for it"))
        assertTrue(out.any { it.contains("'Y1500'") })
    }

    @Test fun urlWrapMarkedAtTheBreak() {
        val lines = listOf(
            "the repo is at github.com/saidi-art/",
            "v-ocr-android releases and",
            "more text here"
        )
        val out = Flags.urlWraps(lines)
        assertEquals(4, out.size)
        assertEquals("the repo is at github.com/saidi-art/", out[0])
        assertTrue(out[1].startsWith("[ScrollRSI: a link wraps"))
        assertEquals("v-ocr-android releases and", out[2])
    }

    @Test fun urlWrapIgnoresPlainProse() {
        val lines = listOf(
            "the quick brown",
            "fox jumps over",
            "a well-",
            "known place"
        )
        assertEquals(lines, Flags.urlWraps(lines))
    }

    @Test fun dedupeDropsOnlyConsecutiveWordSizedRepeats() {
        val lines = listOf("camera series in Jan-", "Jun 2026.", "Jun 2026.", "ha", "ha", "next")
        assertEquals(listOf("camera series in Jan-", "Jun 2026.", "ha", "ha", "next"), Flags.dedupe(lines))
    }

    @Test fun applyAppendsComposerNoteOnlyForSingleFrame() {
        val lines = listOf("hello there")
        assertEquals(listOf("hello there"), Flags.apply(lines, singleFrame = false))
        val out = Flags.apply(lines, singleFrame = true)
        assertEquals(2, out.size)
        assertEquals(Flags.COMPOSER_NOTE, out[1])
    }

    @Test fun flagsNeverTouchMarkerLines() {
        val lines = listOf("[ScrollRSI: possible gap - up to ~96 px of content may be missing here]", "E200 budget")
        val out = Flags.currency(lines)
        assertTrue(out[0].startsWith("[ScrollRSI: possible gap"))
        assertTrue(out[1].contains("'E200'"))
        assertEquals("E200 budget", out[2])
    }

    @Test fun urlWrapIgnoresIntactSingleLineUrl() {
        // The Cloud Shell false fire: a complete address on one line, ordinary words on the next - not a wrap.
        val lines = listOf(
            "your shell is at shell.cloud.google.com",
            "projects and dashboards load there",
            "visit https://github.com today",
            "the readme lists the builds"
        )
        assertEquals(lines, Flags.urlWraps(lines))
    }

    @Test fun urlWrapStillCatchesDomainSplitAndTrailingPunct() {
        val lines = listOf(
            "the repo is at github.",
            "com/saidi-art/v-ocr-android now"
        )
        val out = Flags.urlWraps(lines)
        assertTrue(out[1].startsWith("[ScrollRSI: a link wraps"))
    }

    @Test fun urlWrapIgnoresWordBeforeIntactUrl() {
        // The GitHub-topics false fire on build 102/103: OCR of "intelligence" right before an intact one-line address.
        val lines = listOf(
            "intlieneess",
            "github.com/topics/agentic-ai",
            "generatnndalwradla"
        )
        assertEquals(lines, Flags.urlWraps(lines))
    }

    @Test fun urlWrapIgnoresHyphenatedWordBeforeUrl() {
        val lines = listOf(
            "Significant-Gravitas",
            "github.com/topics/agentic-ai"
        )
        assertEquals(lines, Flags.urlWraps(lines))
    }

    @Test fun urlWrapCatchesDottedFragmentAndLeadingDot() {
        // domain completes only when the sides join, and the left side carries dotted evidence
        val out = Flags.urlWraps(listOf("your shell is at shell.cloud", "google.com dashboards"))
        assertTrue(out[1].startsWith("[ScrollRSI: a link wraps"))
        val out2 = Flags.urlWraps(listOf("your shell is at shell.cloud", ".google.com dashboards"))
        assertTrue(out2[1].startsWith("[ScrollRSI: a link wraps"))
    }

    @Test fun addressBarEllipsisIsCutOffNotWrapped() {
        val lines = listOf(
            "shell.cloud.googl…",
            "the console loaded fine"
        )
        val out = Flags.urlWraps(lines)
        assertEquals(3, out.size)
        assertTrue(out[1].contains("cut off"))
        assertFalse(out[1].contains("wraps"))
    }

    @Test fun proseEllipsisStaysQuiet() {
        val lines = listOf("and then he said...", "nothing more")
        assertEquals(lines, Flags.urlWraps(lines))
    }
}

class FlagsSeamTest {
    @Test fun `adjacent short punctuated repeats collapse - the of x6 ghost`() {
        val out = Flags.dedupe(listOf("the page", "of.", "of.", "of.", "of.", "of.", "of.", "next line here"))
        assertEquals(listOf("the page", "of.", "next line here"), out)
    }

    @Test fun `word-sized repeats stay - people write ok twice`() {
        val out = Flags.dedupe(listOf("ok", "ok", "ha", "ha"))
        assertEquals(listOf("ok", "ok", "ha", "ha"), out)
    }

    @Test fun `a sliced line next to its intact copy is dropped`() {
        val out = Flags.seamFragments(listOf("First paragraph of the answer stands here", "TTPS works.", "HTTPS works.", "another complete sentence follows"))
        assertEquals(listOf("First paragraph of the answer stands here", "HTTPS works.", "another complete sentence follows"), out)
    }

    @Test fun `a shorter line that a longer one merely continues is kept`() {
        val inList = listOf("See you", "See you soon!", "Read more", "Read more about our services")
        assertEquals(inList, Flags.seamFragments(inList))
    }

    @Test fun `genuine short lines are kept`() {
        val inList = listOf("Thanks!", "See you tomorrow at nine", "No", "Sounds good to me")
        assertEquals(inList, Flags.seamFragments(inList))
    }

    @Test fun `list siblings that merely resemble each other are kept`() {
        val inList = listOf("1. First item of the list", "2. Second item of the list", "3. Third item of the list")
        assertEquals(inList, Flags.seamFragments(inList))
    }

    @Test fun `markers are never dropped`() {
        val inList = listOf(
            "[ScrollRSI: page changed during capture - text around this line may mix versions]",
            "x page changed during capture y text around this line may mix z"
        )
        assertEquals(2, Flags.seamFragments(inList).size)
    }
}
