package app.vocr

import app.vocr.core.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TitleTest {
    @Test fun skipsChromeAndTimes() {
        val t = "Tools\nG Grounding with Go\nFWE7:50\nInstinct\nNative it is. Key-gate's dead then - nothing\nto share until the app exists."
        assertEquals("Native it is. Key-gate's dead then - nothing", Title.from(t, "x"))
    }
    @Test fun cleansIllegalCharsAndCuts() {
        val t = "Report: Q3 / revenue* by region? <draft> with a very long trailing sentence that keeps going and going"
        val s = Title.from(t, "x")
        assertTrue(s.length <= 60); assertTrue(s.none { it in "/:*?<>\\|\"" })
    }
    @Test fun fallbackWhenEmpty() { assertEquals("V-OCR 1", Title.from("\n\n", "V-OCR 1")) }
    @Test fun realChatExport() {
        val f = File("/downloads/file-e5db12af.txt"); if (!f.exists()) return
        println("TITLE=" + Title.from(f.readText(), "x"))
    }
}
