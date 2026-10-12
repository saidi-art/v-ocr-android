package app.vocr.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

// (retrigger CI)
/** Titles a result by its content and saves it as Downloads/V-OCR/<title>.txt. Shared by the app flow and the recorder. */
object Saver {
    data class Saved(val title: String, val uri: Uri?)

    fun save(ctx: Context, text: String): Saved {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US).format(java.util.Date())
        val title = Title.from(text, "V-OCR $stamp")
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/V-OCR")
        }
        val uri = runCatching {
            ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)?.also { u ->
                ctx.contentResolver.openOutputStream(u)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
        }.getOrNull()
        return Saved(title, uri)
    }
}
