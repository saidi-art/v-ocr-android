package app.vocr.core

/**
 * The coverage receipt: a plain-language account of how a long capture was put together, written at the end of its text file.
 * It reports only what the run's own evidence supports. The phone's scroll distance is a measurement, never proof; "verified"
 * is used only for joins the images themselves confirmed. It never claims a capture is complete or guaranteed free of gaps.
 */
object Receipt {
    enum class End { BOTTOM, USER, CAP, LOST }
    class Counts(val frames: Int, val parts: Int, val pixelSeams: Int, val phoneSeams: Int, val positionSeams: Int, val gaps: Int, val rows: Int, val end: End, val maxRows: Int)

    fun text(c: Counts): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("ScrollRSI coverage receipt\n")
        sb.append(c.frames).append(if (c.frames == 1) " screen captured" else " screens captured")
        sb.append(", joined into ").append(c.parts).append(if (c.parts == 1) " image part" else " image parts")
        sb.append(" (").append(c.rows).append(" rows).\n")
        val seams = c.pixelSeams + c.phoneSeams + c.positionSeams
        if (seams == 0) sb.append("No joins: a single screen.\n")
        else {
            sb.append(seams).append(if (seams == 1) " join:\n" else " joins:\n")
            if (c.pixelSeams > 0) sb.append("- ").append(c.pixelSeams).append(" checked against the images: matched.\n")
            if (c.phoneSeams > 0) sb.append("- ").append(c.phoneSeams).append(" placed by the phone's own distance measure: the images could not confirm ").append(if (c.phoneSeams == 1) "this join" else "these joins").append(".\n")
            if (c.positionSeams > 0) sb.append("- ").append(c.positionSeams).append(" accepted on position only: the page changed while scrolling, and text there may mix versions (marked in the text).\n")
        }
        if (c.gaps > 0) sb.append(c.gaps).append(if (c.gaps == 1) " known gap: a section may be missing" else " known gaps: sections may be missing").append(" (marked in the text; the image splits into parts there).\n")
        else sb.append("No known gaps between the captured screens.\n")
        when (c.end) {
            End.BOTTOM -> sb.append("End: the page stopped moving; the bottom was reached.\n")
            End.USER -> sb.append("End: stopped by you. Anything below was not captured.\n")
            End.CAP -> sb.append("End: stopped at the safety limit of ").append(c.maxRows).append(" rows. Anything below was not captured.\n")
            End.LOST -> sb.append("End: the capture lost the page and stopped rather than guess. Anything below was not captured.\n")
        }
        if (seams > 0 && c.gaps == 0 && c.phoneSeams == 0 && c.positionSeams == 0 && c.end == End.BOTTOM)
            sb.append("Every join in this capture is verified against the images.\n")
        sb.append("Not proven: content that changed between captures, and text too small, blurred or styled to read reliably.")
        return sb.toString()
    }
}
