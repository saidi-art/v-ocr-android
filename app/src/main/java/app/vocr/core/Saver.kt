package app.vocr.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** Titles a result by its content and saves it as Downloads/ScrollRSI/<title>.txt. Shared by the app flow and the recorder. */
object Saver {
    data class Saved(val title: String, val uri: Uri?, val error: String = "")

    fun save(ctx: Context, text: String): Saved {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US).format(java.util.Date())
        val dict = runCatching { ctx.assets.open("words.txt").use { String(it.readBytes(), Charsets.UTF_8) }.split("\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet() }.getOrNull()
        val title = Title.from(text, "ScrollRSI $stamp", dict)
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ScrollRSI")
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

    /** Saves text under an exact title in Downloads/ScrollRSI (used by the optional Clean copy). */
    fun saveAs(ctx: Context, title: String, text: String): Saved {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ScrollRSI")
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
}
