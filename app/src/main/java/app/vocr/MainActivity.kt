package app.vocr

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.vocr.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0B0E17)
val Card = Color(0xFF151A2A)
val Violet = Color(0xFF7C5CFF)
val Lilac = Color(0xFFB7A8FF)
val Muted = Color(0xFF8A93AD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Ink, surface = Card)) { Home() } }
    }
}

data class Picked(val uri: Uri?, val name: String, val seconds: Int, val w: Int, val h: Int)

fun describe(ctx: android.content.Context, uri: Uri): Picked {
    var name = "recording"
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) name = c.getString(i)
    }
    val r = MediaMetadataRetriever()
    r.setDataSource(ctx, uri)
    fun meta(k: Int) = r.extractMetadata(k)?.toIntOrNull() ?: 0
    val p = Picked(uri, name, meta(MediaMetadataRetriever.METADATA_KEY_DURATION) / 1000,
        meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
    r.release(); return p
}

@Composable
fun Home(preview: Picked? = null) {
    val ctx = LocalContext.current
    var picked by remember { mutableStateOf(preview) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var result by remember { mutableStateOf<Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { result = null; error = null; picked = runCatching { describe(ctx, uri) }.getOrNull() }
    }
    fun start(p: Picked) {
        progress = Progress("Starting", 0, 1); result = null; error = null
        Thread {
            try {
                val r = Pipeline.run(ctx, p.uri, { pr -> progress = pr })
                result = r
            } catch (e: Throwable) { error = e.message ?: e.javaClass.simpleName }
            progress = null
        }.start()
    }
    LaunchedEffect(Unit) {
        if ((ctx as? android.app.Activity)?.intent?.getBooleanExtra("demo", false) == true && progress == null && result == null) {
            val p = Picked(null, "demo.mp4 (built-in test video)", 9, 540, 960); picked = p; start(p)
        }
    }
    val res = result
    if (res != null) { Strips(res) { result = null }; return }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, Color(0xFF111631)))).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(Modifier.height(32.dp))
        Row {
            Text("V-", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("OCR", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Violet)
        }
        Text("If you can scroll it, you can save it.", color = Muted, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Card).padding(24.dp)) {
            Icon(Icons.Filled.VideoLibrary, null, tint = Lilac, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(picked?.name ?: "Pick a screen recording", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                picked?.let { "${it.seconds / 60}m ${it.seconds % 60}s  -  ${it.w}x${it.h}" } ?: "Free, offline and private. Nothing leaves your phone.",
                color = Muted, fontSize = 14.sp
            )
            val pr = progress
            if (pr != null) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { pr.done / pr.total.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth(), color = Violet, trackColor = Ink)
                Spacer(Modifier.height(6.dp))
                Text("${pr.stage}  ${pr.done}/${pr.total}", color = Muted, fontSize = 13.sp)
            }
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFFF7A7A), fontSize = 13.sp) }
            Spacer(Modifier.height(20.dp))
            val p = picked
            if (p != null && progress == null) {
                Button(
                    onClick = { start(p) },
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White)
                ) { Text("Extract strips (M1 test)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                enabled = progress == null,
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = if (picked == null) ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White)
                         else ButtonDefaults.buttonColors(containerColor = Color(0xFF232A45), contentColor = Color.White)
            ) { Text(if (picked == null) "Choose video" else "Choose another", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(16.dp))
        Text("v0.2.1-m1b  -  frame extraction test", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun Strips(res: Result, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).systemBarsPadding().padding(16.dp)) {
        Text("Kept ${res.strips.size} strips", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "${res.seconds}s video, ${res.framesTotal} frames read, ${res.dup} skipped (dup/accumulating), ${res.full} full, ${res.cropped} cropped. " +
                "Took ${res.ms / 1000.0}s",
            color = Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White)) { Text("Back") }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(res.strips) { s ->
                val bm = remember(s.file) { BitmapFactory.decodeFile(s.file.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Card).padding(8.dp)) {
                    Text("${s.kind}  t=${"%.1f".format(s.t)}s  ${bm.width * 2}x${bm.height * 2}", color = Lilac, fontSize = 12.sp)
                    Image(bm.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
        }
    }
}
