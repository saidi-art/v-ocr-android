package app.vocr

import app.vocr.core.Receipt
import org.junit.Assert.*
import org.junit.Test

class ReceiptTest {
    private fun counts(pixel: Int = 13, phone: Int = 0, position: Int = 0, gaps: Int = 0, end: Receipt.End = Receipt.End.BOTTOM) =
        Receipt.Counts(frames = pixel + phone + position + 1, parts = 2, pixelSeams = pixel, phoneSeams = phone, positionSeams = position, gaps = gaps, rows = 30000, end = end, maxRows = 40000)

    @Test fun fullyVerifiedRunSaysSo() {
        val t = Receipt.text(counts())
        assertTrue(t, t.contains("Every join in this capture is verified against the images."))
        assertTrue(t, t.contains("13 checked against the images: matched."))
        assertTrue(t, t.contains("the bottom was reached"))
    }
    @Test fun phonePlacedJoinsAreNotCalledVerified() {
        val t = Receipt.text(counts(pixel = 10, phone = 3))
        assertTrue(t, t.contains("the images could not confirm"))
        assertFalse(t, t.contains("verified against the images"))
    }
    @Test fun positionOnlyJoinsFlagMixedVersions() {
        val t = Receipt.text(counts(pixel = 9, position = 2))
        assertTrue(t, t.contains("accepted on position only"))
        assertFalse(t, t.contains("verified against the images"))
    }
    @Test fun manualStopSaysBelowNotCaptured() {
        val t = Receipt.text(counts(end = Receipt.End.USER))
        assertTrue(t, t.contains("stopped by you"))
        assertFalse(t, t.contains("verified against the images"))
    }
    @Test fun gapsAreReportedAsKnown() {
        val t = Receipt.text(counts(gaps = 2))
        assertTrue(t, t.contains("2 known gaps"))
        assertFalse(t, t.contains("verified against the images"))
    }
    @Test fun singleScreenHasNoJoins() {
        val t = Receipt.text(counts(pixel = 0))
        assertTrue(t, t.contains("No joins: a single screen."))
    }
    @Test fun neverPromisesCompleteness() {
        for (e in Receipt.End.values()) {
            val t = Receipt.text(counts(pixel = 5, phone = 2, position = 1, gaps = 1, end = e)).lowercase()
            assertFalse(t, t.contains("guarantee"))
            assertFalse(t, t.contains("100%"))
            assertFalse(t, t.contains("provably complete"))
        }
    }
}
