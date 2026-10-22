package app.vocr

import app.vocr.core.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TitleTest {
    @Test fun skipsChromeAndTimes() {
        val t = "Tools\nG Grounding with Go\nFWE7:50\nInstinct\nNative it is. Key-gate's dead then - nothing\nto share until the app exists."
        assertEquals("Instinct - Native it is. Key-gate's dead then - nothing", Title.from(t, "x"))
    }
    @Test fun cleansIllegalCharsAndCuts() {
        val t = "Report: Q3 / revenue* by region? <draft> with a very long trailing sentence that keeps going and going"
        val s = Title.from(t, "x")
        assertTrue(s.length <= 60); assertTrue(s.none { it in "/:*?<>\\|\"" })
    }
    @Test fun noHeaderWhenContentIsFirst() {
        assertEquals("Quarterly revenue grew twelve percent this year", Title.from("Quarterly revenue grew twelve percent this year\nmore text here", "x"))
    }
    @Test fun skipsChromeWordAsHeader() {
        assertEquals("Meeting notes - The launch is moved to the second week", Title.from("Meeting notes\nMessage\nThe launch is moved to the second week", "x"))
    }
    @Test fun headerAndContentCutAt60() {
        val s = Title.from("Instinct\nNative it is and this line goes on and on far past the sixty character limit", "x")
        assertTrue(s.length <= 60); assertTrue(s.startsWith("Instinct - Native it is"))
    }
    @Test fun fallbackWhenEmpty() { assertEquals("V-OCR 1", Title.from("\n\n", "V-OCR 1")) }
    @Test fun realChatExport() {
        val f = File("/downloads/file-e5db12af.txt"); if (!f.exists()) return
        println("TITLE=" + Title.from(f.readText(), "x"))
    }
}

class TitleCleanTest {
    private val words = File("src/main/assets/words.txt").readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
    @Test fun fastExportPicksCleanLine() {
        val f = File("/downloads/file-923ec9a9.txt")
        if (!f.exists()) return
        val t = Title.from(f.readText(), "x", words)
        println("FASTTITLE " + t)
        assertTrue(t.startsWith("Instinct - Native it is"))
    }
    @Test fun cleanTextUnchanged() {
        val t = "Instinct\nNative it is. Key-gate's dead then - nothing\nto share until the app exists."
        assertEquals("Instinct - Native it is. Key-gate's dead then - nothing", Title.from(t, "x", words))
    }

    @org.junit.Test fun pageExtensionIsDropped() {
        val r = app.vocr.core.Title.from("release - Sphere palette - 365 days (2).html\nmore text here", "fb")
        org.junit.Assert.assertFalse(r, r.endsWith(".html"))
    }

    @org.junit.Test fun fileListIsNotATitle() {
        val list = "6:36\n45\nDownloads\n18.24 GB\nToday\nvocr-release-88.apk\ngithub.com\n75.12 MB\n17 Oct 6:35 am\nvocr-release-87.apk\ngithub.com\n75.12 MB\n17 Oct 5:42 am"
        org.junit.Assert.assertTrue(app.vocr.core.Title.looksLikeList(list))
        org.junit.Assert.assertFalse(app.vocr.core.Title.looksLikeList("If you can scroll it, you can save it\nScrollRSI turns scrolling screens into text and pictures.\nFree and private."))
    }
    @Test fun markerLinesNeverBecomeTitle() {
        val t = "github.com/topics/agen\n[ScrollRSI: a link wraps across this line break - characters may be missing where it joins]\ngithub.com/topics/agentic-ai\nHere are 29,785 public repositories matching this topic..."
        val s = Title.from(t, "x")
        assertFalse(s.contains("ScrollRSI"))
        assertTrue(s.contains("Here are 29,785"))
    }
    @Test fun markerOnlyTextFallsBack() {
        assertEquals("fb", Title.from("[ScrollRSI: anything hidden behind a bottom bar (composer, keyboard, toolbar) may be missing from this screenshot]", "fb"))
    }
}

