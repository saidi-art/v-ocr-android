package app.vocr.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** Titles a result by its content and saves it as Downloads/V-OCR/<title>.txt. Shared by the app flow and the recorder. */
object Saver {
    data class Saved(val title: String, val uri: Uri?, val error: String = "")

    fun save(ctx: Context, text: String): Saved {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US).format(java.util.Date())
        val dict = runCatching { ctx.assets.open("words.txt").use { String(it.readBytes(), Charsets.UTF_8) }.split("\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet() }.getOrNull()
        val title = Title.from(text, "V-OCR $stamp", dict)
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/V-OCR")
        }
        var err = ""
        val uri = runCatching {
            ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)?.also { u ->
                val os = ctx.contentResolver.openOutputStream(u) ?: throw java.io.IOException("could not open the new file for writing")
                os.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } ?: throw java.io.IOException("Android refused to create the file in Downloads")
        }.onFailure { err = it.message ?: it.javaClass.simpleName }.getOrNull()
        return Saved(title, uri, err)
    }

    /** Debug option (default OFF): copies a widget recording (rec-*.mp4) to Downloads/V-OCR before it is deleted.
     *  Returns a short human result: "" when the toggle is off or there is nothing to copy, else "Recording saved to ..." or "Recording NOT saved: <why>". */
    fun keepRecording(ctx: Context, f: java.io.File): String {
        if (!ctx.getSharedPreferences("vocr", Context.MODE_PRIVATE).getBoolean("keeprec", false)) return ""
        if (!f.name.startsWith("rec-")) return "Recording NOT saved: only recordings made with the floating button are kept"
        if (!f.exists()) return "Recording NOT saved: the recording file was already gone"
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.US).format(java.util.Date())
        val name = "V-OCR recording $stamp.mp4"
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/V-OCR")
        }
        return runCatching {
            val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw java.io.IOException("Android refused to create the file")
            val os = ctx.contentResolver.openOutputStream(u) ?: throw java.io.IOException("could not open the new file")
            var n = 0L
            os.use { o -> f.inputStream().use { n = it.copyTo(o) } }
            "Recording saved to Download/V-OCR/$name (${n / 1024 / 1024} MB)"
        }.getOrElse { "Recording NOT saved: " + (it.message ?: it.javaClass.simpleName) }
    }
}
