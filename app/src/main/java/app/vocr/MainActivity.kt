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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.vocr.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) JobQueue.addUris(ctx, uris)
    }
    val addVideos = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
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
        if ((ctx as? android.app.Activity)?.intent?.getBooleanExtra("queue", false) == true && JobQueue.jobs.isEmpty()) { JobQueue.addDemo(ctx); JobQueue.addDemo(ctx) }
        if ((ctx as? android.app.Activity)?.intent?.getBooleanExtra("demo", false) == true && progress == null && result == null) {
            val p = Picked(null, "demo.mp4 (built-in test video)", 9, 540, 960); picked = p; start(p)
        }
    }
    val res = result
    if (res != null) { Strips(res, (ctx as? android.app.Activity)?.intent?.getBooleanExtra("read", false) == true) { result = null }; return }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, Color(0xFF111631)))).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Spacer(Modifier.height(16.dp))
                Row {
                    Text("V-", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("OCR", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Violet)
                }
                Text("If you can scroll it, you can save it.", color = Muted, fontSize = 16.sp)
            }
            AddButton(JobQueue.activeCount()) { addVideos() }
        }
        Spacer(Modifier.height(12.dp))
        QueueList()
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Card).padding(24.dp)) {
            Text("\u25B6", color = Lilac, fontSize = 32.sp)
            Spacer(Modifier.height(12.dp))
            Text(picked?.name ?: "Pick screen recordings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
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
                val eta = if (pr.etaS >= 0) "  ~${pr.etaS / 60}m ${pr.etaS % 60}s left" else ""
            Text("${pr.stage}  ${pr.done}/${pr.total}$eta  ${pr.lines} lines", color = Muted, fontSize = 13.sp)
            if (pr.live.isNotEmpty()) Text(pr.live.joinToString("\n"), color = Lilac, fontSize = 12.sp, maxLines = 6, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Card).padding(8.dp))
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
                onClick = { addVideos() },
                enabled = progress == null,
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = if (picked == null) ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White)
                         else ButtonDefaults.buttonColors(containerColor = Color(0xFF232A45), contentColor = Color.White)
            ) { Text("Choose videos (adds to the queue)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(10.dp))
        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        Button(
            onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 33) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                if (!android.provider.Settings.canDrawOverlays(ctx)) {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    ctx.startForegroundService(android.content.Intent(ctx, app.vocr.capture.CaptureService::class.java).setAction(app.vocr.capture.CaptureService.ACTION_WIDGET))
                    (ctx as? android.app.Activity)?.moveTaskToBack(true)
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232A45), contentColor = Color.White)
        ) { Text(if (android.provider.Settings.canDrawOverlays(ctx)) "Floating recorder" else "Floating recorder (allow overlay)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
            modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232A45), contentColor = Color.White)
        ) { Text(if (app.vocr.capture.ScrollService.instance != null) "Auto-scroll: enabled (long-press the bubble)" else "Auto-scroll: turn on in Accessibility", fontSize = 14.sp) }
        Spacer(Modifier.height(10.dp))
        var shots by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("shots", false)) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Switch(checked = shots, onCheckedChange = { shots = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("shots", it).apply() })
            Spacer(Modifier.width(8.dp))
            Text("Settings: show extracted screenshots", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text("v" + (runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?") + "  -  strips + on-device text", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun Strips(res: Result, autoRead: Boolean = false, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var reading by remember { mutableStateOf<Progress?>(null) }
    var read by remember { mutableStateOf<ReadResult?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var fast by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("fast", false)) }
    fun startRead() {
        reading = Progress("Loading reader", 0, res.strips.size); err = null
        Thread {
            try {
                val rr = Reader.readAll(ctx, res.strips, fast) { reading = it }
                val sv = app.vocr.core.Saver.save(ctx, rr.text)
                read = rr.copy(savedTitle = sv.title, savedOk = sv.uri != null)
            }
            catch (e: Throwable) { err = (e.message ?: e.javaClass.simpleName) }
            reading = null
        }.start()
    }
    LaunchedEffect(Unit) { if (autoRead && read == null && reading == null) startRead() }
    val rd = read
    if (rd != null) { TextScreen(rd) { read = null }; return }
    Column(Modifier.fillMaxSize().background(Ink).systemBarsPadding().padding(16.dp)) {
        Text("Kept ${res.strips.size} strips", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "${res.seconds}s video, ${res.framesTotal} frames read, ${res.dup} skipped (dup/accumulating), ${res.full} full, ${res.cropped} cropped. " +
                "Took ${res.ms / 1000.0}s  ${res.timing}",
            color = Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232A45), contentColor = Color.White)) { Text("Back") }
            Button(
                enabled = reading == null,
                onClick = { startRead() },
                colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White)
            ) { Text("Read text") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = fast, onCheckedChange = { fast = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("fast", it).apply() }, enabled = reading == null)
            Spacer(Modifier.width(8.dp))
            Text("Fast mode (about 25% quicker, a little less accurate)", color = Muted, fontSize = 12.sp)
        }
        var bench by remember { mutableStateOf<List<String>>(emptyList()) }
        var benching by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = reading == null && !benching, onClick = {
                benching = true; bench = listOf("Running...")
                Thread {
                    val acc = ArrayList<String>()
                    try { Reader.bench(ctx, res.strips) { acc.add(it); bench = acc.toList() } } catch (e: Throwable) { acc.add("failed: " + (e.message ?: e.javaClass.simpleName)); bench = acc.toList() }
                    benching = false
                }.start()
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232A45), contentColor = Color.White)) { Text("Speed test") }
            Spacer(Modifier.width(8.dp))
            Text(bench.joinToString("\n"), color = Lilac, fontSize = 12.sp)
        }
        reading?.let { pr ->
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { pr.done / pr.total.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth(), color = Violet, trackColor = Card)
            Text("${pr.stage}  ${pr.done}/${pr.total}", color = Muted, fontSize = 13.sp)
        }
        err?.let { Text(it, color = Color(0xFFFF7A7A), fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        val showShots = app.vocr.capture.CaptureService.prefs(ctx).getBoolean("shots", false)
        if (!showShots) Text("Screenshots hidden (turn on \"show extracted screenshots\" on the home screen).", color = Muted, fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(if (showShots) res.strips else emptyList()) { s ->
                val bm = remember(s.file) { BitmapFactory.decodeFile(s.file.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Card).padding(8.dp)) {
                    Text("${s.kind}  t=${"%.1f".format(s.t)}s  ${bm.width * 2}x${bm.height * 2}", color = Lilac, fontSize = 12.sp)
                    Image(bm.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
        }
    }
}

@Composable
fun TextScreen(r: ReadResult, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Ink).systemBarsPadding().padding(16.dp)) {
        Text("Text from ${r.strips} strips", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${r.rawLines} lines read, ${r.mergedLines} after removing overlap. Took ${r.ms / 1000.0}s  (${r.detail})", color = Muted, fontSize = 13.sp)
        if (r.savedTitle.isNotEmpty())
            Text(if (r.savedOk) "Saved to Downloads/V-OCR/${r.savedTitle}.txt" else "Could not save the file (use Copy or Share)", color = Color(0xFF9DFFB0), fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232A45), contentColor = Color.White)) { Text("Back") }
            Button(onClick = {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("V-OCR", r.text))
            }, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White)) { Text("Copy") }
            Button(onClick = {
                // Share as an attached .txt (not pasted text), so chat apps receive a file.
                val name = (r.savedTitle.ifEmpty { "V-OCR" }).replace(Regex("[^A-Za-z0-9 ._-]"), "_") + ".txt"
                val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = java.io.File(dir, name).apply { writeText(r.text) }
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".share", f)
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri(name, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(android.content.Intent.createChooser(i, "Share text file"))
            }, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White)) { Text("Share file") }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(r.text, color = Color.White, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp))
        }
    }
}

/** Circled plus, top right: opens the picker and puts the chosen videos on the queue. Badge = jobs waiting or running. */
@Composable
fun AddButton(count: Int, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Card).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(34.dp)) {
            val c = center; val r = size.minDimension / 2
            drawCircle(Color.White, r - 1.5f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            val a = r * 0.45f
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(c.x - a, c.y), androidx.compose.ui.geometry.Offset(c.x + a, c.y), strokeWidth = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(c.x, c.y - a), androidx.compose.ui.geometry.Offset(c.x, c.y + a), strokeWidth = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        if (count > 0) Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Violet), contentAlignment = Alignment.Center) {
            Text(count.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun QueueList() {
    val ctx = LocalContext.current
    val jobs = JobQueue.jobs
    if (jobs.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(Card).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (jobs.any { it.state >= 2 }) Text("Clear finished", color = Lilac, fontSize = 13.sp, modifier = Modifier.clickable { JobQueue.clearFinished() })
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(jobs.toList(), key = { it.id }) { j ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (j.state == 2 && j.title.isNotEmpty()) j.title else j.label, color = Color.White, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        if (j.state <= 1) Text("Cancel", color = Color(0xFFFF7A7A), fontSize = 13.sp, modifier = Modifier.clickable { JobQueue.cancel(j) }.padding(4.dp))
                    }
                    when (j.state) {
                        0 -> Text("Waiting", color = Muted, fontSize = 12.sp)
                        1 -> { LinearProgressIndicator(progress = { j.frac }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Violet, trackColor = Card); Text(j.stage, color = Muted, fontSize = 12.sp) }
                        2 -> {
                            Text("${j.lines} lines in ${j.secs}s - saved to Downloads/V-OCR", color = Color(0xFF9DFFB0), fontSize = 12.sp)
                            val u = j.result
                            if (u != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                                Text("Open", color = Lilac, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable {
                                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(u, "text/plain").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                })
                                Text("Share file", color = Lilac, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable {
                                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_STREAM, u)
                                        clipData = android.content.ClipData.newRawUri(j.title, u)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    ctx.startActivity(android.content.Intent.createChooser(i, "Share text file"))
                                })
                            }
                        }
                        else -> Text(j.stage, color = if (j.state == 3) Color(0xFFFF7A7A) else Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
