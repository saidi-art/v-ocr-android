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
import androidx.compose.foundation.background
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

data class Picked(val name: String, val seconds: Int, val w: Int, val h: Int)

fun describe(ctx: android.content.Context, uri: Uri): Picked {
    var name = "recording"
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) name = c.getString(i)
    }
    val r = MediaMetadataRetriever()
    r.setDataSource(ctx, uri)
    fun meta(k: Int) = r.extractMetadata(k)?.toIntOrNull() ?: 0
    val p = Picked(name, meta(MediaMetadataRetriever.METADATA_KEY_DURATION) / 1000,
        meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
    r.release(); return p
}

@Composable
fun Home(preview: Picked? = null) {
    val ctx = LocalContext.current
    var picked by remember { mutableStateOf(preview) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) picked = runCatching { describe(ctx, uri) }.getOrNull()
    }
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
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Violet)
            ) { Text(if (picked == null) "Choose video" else "Choose another", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(16.dp))
        Text("v0.1.0-m0  -  skeleton build", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
