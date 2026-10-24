package app.vocr.sphere

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sphere's folder (brief: "Hand-off and folder"). Every capture is saved on the device in a structured format
 * built now for the intelligent folder it becomes later - without any AI in v1:
 *  - text captures: plain text with a small header block; image captures: standard image files
 *  - header: source app, date and time, format, capture type, and the text source (the user is told which is in use)
 *  - predictable names: sphere-YYYYMMDD-HHmmss-<type>-<source>.<ext>
 *  - deleting a capture is easy: [delete] removes by Uri, from anywhere that shows one
 * Everything stays in Download/Sphere on the phone; nothing here talks to a network.
 */
object CaptureStore {
    enum class CaptureType(val wire: String) { TAP("tap"), AUTO("auto"), RESCUE("rescue"), REC("rec") }
    enum class Format(val wire: String) { TEXT("text"), IMAGE("image") }
    enum class TextSource(val wire: String) { OCR("OCR"), DIRECT("direct text"), NONE("none") }

    /** One capture's facts. [imageUris] holds the image part(s); [textUri] the text, when that format was made. */
    class Meta(
        val type: CaptureType,
        val sourceLabel: String,        // the app the capture came from, as the user knows it ("Grok"); "unknown app" when unknowable
        val sourcePackage: String,      // its package name when Android told us, else ""
        val textSource: TextSource,
        val createdAt: Date = Date(),
        val imageUris: List<Uri> = emptyList(),
        val textUri: Uri? = null,
    ) {
        /** Same capture, carrying a corrected text source (OCR fallback vs direct text). */
        fun withTextSource(ts: TextSource) = Meta(type, sourceLabel, sourcePackage, ts, createdAt, imageUris, textUri)
        /** Same capture, carrying the files it was saved as. */
        fun withFiles(images: List<Uri>, text: Uri?) = Meta(type, sourceLabel, sourcePackage, textSource, createdAt, images, text)

        val stampFile = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(createdAt)
        val stampHuman = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(createdAt)

        /** Predictable base name, source label sanitised so any app name survives a file system. */
        fun baseName(): String {
            val src = sourceLabel.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "unknown" }
            return "sphere-$stampFile-${type.wire}-$src".take(80)
        }
    }

    /** The small header block prepended to every text capture (and used for the image sidecar description). */
    fun header(m: Meta, format: Format): String = buildString {
        append("---\n")
        append("Sphere capture\n")
        append("Source app: ").append(m.sourceLabel).append('\n')
        append("Captured: ").append(m.stampHuman).append('\n')
        append("Format: ").append(format.wire).append('\n')
        append("Capture: ").append(m.type.wire).append('\n')
        if (format == Format.TEXT) append("Text source: ").append(m.textSource.wire).append('\n')
        append("---\n\n")
    }

    private fun insert(ctx: Context, name: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Sphere")
        }
        return runCatching {
            ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)?.also { u ->
                (ctx.contentResolver.openOutputStream(u) ?: throw java.io.IOException("could not open the new file for writing")).use(write)
            } ?: throw java.io.IOException("Android refused to create the file in Downloads")
        }.onFailure { app.vocr.VocrApp.note("capture store save failed: $name", it) }.getOrNull()
    }

    /** Saves a text capture with its header. Returns the Uri (also settable back onto a copy of Meta by the caller). */
    fun saveText(ctx: Context, m: Meta, body: String): Uri? =
        insert(ctx, "${m.baseName()}.txt", "text/plain") { it.write((header(m, Format.TEXT) + body).toByteArray(Charsets.UTF_8)) }

    /** Saves one image part of a capture. [part] 1-based; single-image captures pass 1 of 1. */
    fun saveImage(ctx: Context, m: Meta, part: Int, parts: Int, src: java.io.File, mime: String = "image/png"): Uri? {
        val ext = if (mime == "image/jpeg") "jpg" else "png"
        val name = if (parts <= 1) "${m.baseName()}.$ext" else "${m.baseName()}-part$part-of-$parts.$ext"
        return insert(ctx, name, mime) { o -> src.inputStream().use { it.copyTo(o) } }
    }

    /** A capture's companion file (e.g. its run log), named <base>-<suffix>.txt so it sorts next to the capture. */
    fun saveCompanion(ctx: Context, m: Meta, suffix: String, body: String): Uri? =
        insert(ctx, "${m.baseName()}-${suffix.replace(Regex("[^A-Za-z0-9]+"), "-")}.txt", "text/plain") { it.write(body.toByteArray(Charsets.UTF_8)) }

    /** Easy delete (brief): removes one saved capture file. Returns false when Android refuses. */
    fun delete(ctx: Context, uri: Uri): Boolean = runCatching { ctx.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    // ---- hand-off journey measurement ---------------------------------------------------------------------------
    // The one-or-two-tap promise is proven, not assumed: every capture -> review -> destination run is logged step
    // by step to a local CSV (never leaves the phone). Testing reads it to count the real actions per hand-off.
    private const val LOG = "handoff-journey.csv"

    /** Appends one journey step. [captureId] is the capture's base name; [step] e.g. capture-ready, open, share-sheet, sent. */
    fun logStep(ctx: Context, captureId: String, step: String, detail: String = "") {
        runCatching {
            val f = java.io.File(ctx.filesDir, LOG)
            if (!f.exists()) f.writeText("time,capture,step,detail\n")
            val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$t,$captureId,$step,${detail.replace(",", ";")}\n")
        }
    }

    /** The journey log's contents (for a test build's diagnostics), or null when nothing was logged yet. */
    fun journeyLog(ctx: Context): String? = runCatching { java.io.File(ctx.filesDir, LOG).takeIf { it.exists() }?.readText() }.getOrNull()
}
