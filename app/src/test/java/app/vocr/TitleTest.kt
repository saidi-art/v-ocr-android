package app.vocr

import app.vocr.core.Title
import org.junit.Assert.assertEquals
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
}
