package app.vocr

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** In-app list of saved captures (Downloads/ScrollRSI) with a viewer. Back always returns to the app home. */
class SavedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScrollOnTheme() { SavedScreen { finish() } } }
    }
}

private fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4" -> "video/mp4"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "txt" -> null; else -> null
}

private fun readText(ctx: android.content.Context, u: Uri, size: Long): String {
    if (size > 5_000_000L) return "This file is too large to show here (" + size / 1048576 + " MB). Use Share or the Files app."
    return runCatching { ctx.contentResolver.openInputStream(u)?.use { s -> String(s.readBytes(), Charsets.UTF_8) } }.getOrNull() ?: "Could not read this file."
}

private data class Item(val uri: Uri, val name: String, val size: Long, val date: Long)

private fun loadItems(ctx: android.content.Context): List<Item> {
    val out = ArrayList<Item>()
    val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED)
    runCatching {
        ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("Download/ScrollRSI%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) out.add(Item(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)), c.getString(1) ?: "?", c.getLong(2), c.getLong(3) * 1000))
        }
    }
    return out
}

@Composable
private fun SavedScreen(onExit: () -> Unit) {
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Item?>(null) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { loadItems(ctx) }; loaded = true
        (ctx as? android.app.Activity)?.intent?.data?.let { u ->   // opened from a "Text ready" notification
            text = withContext(Dispatchers.IO) { readText(ctx, u, 0L) }
            open = items.firstOrNull { it.uri == u } ?: Item(u, "Saved text", text.length.toLong(), System.currentTimeMillis())
        }
    }
    BackHandler(enabled = open != null) { open = null }
    val fmt = remember { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()) }
    val sec = Btn2
    Column(Modifier.fillMaxSize().scrollOnBackdrop().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (open != null) open = null else onExit() }, colors = ButtonDefaults.buttonColors(containerColor = sec, contentColor = TextMain)) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text(if (open == null) "Saved" else open!!.name, color = TextMain, fontSize = if (open == null) 28.sp else 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = DisplayFont, maxLines = 2, modifier = Modifier.weight(1f))
        }
        val cur = open
        if (cur == null) {
            if (loaded && items.isEmpty()) Text("Nothing saved yet. Finished captures appear here.", color = Muted, modifier = Modifier.padding(20.dp))
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items) { it ->
                    val media = mimeOf(it.name)
                    Row(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(18.dp)).hapticClickable {
                        Haptics.secondary(view)
                        val mt = media ?: "text/plain"
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(it.uri, mt).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            .onFailure { android.widget.Toast.makeText(ctx, "No app found to open this file. Use Share or the Files app.", android.widget.Toast.LENGTH_LONG).show() }
                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.name.removeSuffix(".txt"), color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Spacer(Modifier.height(4.dp))
                            Text(fmt.format(java.util.Date(it.date)) + "  -  " + (if (it.size >= 1048576) "${it.size / 1048576} MB" else if (it.size >= 1024) "${it.size / 1024} kB" else "${it.size} B") + (if (media != null) "  -  tap to open" else ""), color = Muted, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType(media ?: "text/plain").putExtra(android.content.Intent.EXTRA_STREAM, it.uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.startActivity(android.content.Intent.createChooser(i, "Share file"))
                        }) { Text("Share", color = TextMain) }
                    }
                }
            }
            Button(onClick = { openFolder(ctx) }, modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = sec, contentColor = TextMain)) { Text("Show in Files app", fontSize = 14.sp) }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(20.dp)).padding(18.dp)) {
                    SelectionContainer { Text(text, color = TextMain, fontSize = 14.sp, lineHeight = 20.sp) }
                }
            }
            if (!cur.name.removeSuffix(".txt").endsWith("(clean)")) {
                Button(onClick = {
                    val r = app.vocr.core.Saver.saveAs(ctx, cur.name.removeSuffix(".txt") + " (clean)", app.vocr.core.ReadingCopy.make(text))
                    if (r.uri != null) { scope.launch { items = withContext(Dispatchers.IO) { loadItems(ctx) } }; android.widget.Toast.makeText(ctx, "Clean copy saved. The original is unchanged.", android.widget.Toast.LENGTH_LONG).show() }
                    else android.widget.Toast.makeText(ctx, "Could not save: " + r.error, android.widget.Toast.LENGTH_LONG).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = sec, contentColor = TextMain),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = "Clean copy. Saves a second, tidied file. The original stays unchanged." }) { Text("Clean copy") }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ScrollRSI", text))
                    android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = sec, contentColor = TextMain), modifier = Modifier.weight(1f)) { Text("Copy") }
                Button(onClick = {
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_STREAM, cur.uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(android.content.Intent.createChooser(i, "Share file"))
                }, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = OnBronze), modifier = Modifier.weight(1f)) { Text("Share file") }
            }
        }
    }
}
