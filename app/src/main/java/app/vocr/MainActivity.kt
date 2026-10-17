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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink: Color get() = Ground
val Card: Color get() = if (Pal.paper) Color(0xFFFDFBF7) else Color(0xFF1A1B20)
val Violet: Color get() = if (Pal.paper) Color(0xFF0C0C0C) else Color(0xFFD2B08A)
val Lilac: Color get() = if (Pal.paper) Color(0xFF0C0C0C) else Color(0xFFE3C7A2)
val Muted: Color get() = if (Pal.paper) Color(0xD60C0C0C) else Color(0xFFA9A6A0)

class MainActivity : ComponentActivity() {
    override fun onResume() { super.onResume(); JobQueue.clearDoneNotifications(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("glassdemo", false)) { setContent { GlassDemo() }; return }
        setContent { ScrollOnTheme() { Home() } }
    }
}

/** Preview of the widget look on a dark and a light background (CI screenshot only; reached with the "glassdemo" extra). */
@Composable
fun GlassDemo() {
    @Composable fun half(bg: Brush, modifier: Modifier) {
        Column(modifier.background(bg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { app.vocr.capture.Bubble(it) }, modifier = Modifier.size(52.dp))
                androidx.compose.ui.viewinterop.AndroidView(factory = { app.vocr.capture.Bubble(it).apply { pressedState = true } }, modifier = Modifier.size(52.dp))
                androidx.compose.ui.viewinterop.AndroidView(factory = { app.vocr.capture.Bubble(it).apply { ring = 0.62f; label = "62%" } }, modifier = Modifier.size(52.dp))
                androidx.compose.ui.viewinterop.AndroidView(factory = { app.vocr.capture.Bubble(it).apply { label = "STOP"; labelSp = 9f; fill = android.graphics.Color.parseColor("#E5484D") } }, modifier = Modifier.size(52.dp))
                androidx.compose.ui.viewinterop.AndroidView(factory = { app.vocr.capture.AddChip(it).apply { count = 3 } }, modifier = Modifier.size(40.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { app.vocr.capture.glassChip(it, "\u00D7", android.graphics.Color.parseColor("#E5484D")) })
                androidx.compose.ui.viewinterop.AndroidView(factory = { app.vocr.capture.glassChip(it, "Slow", android.graphics.Color.parseColor("#25262B")) })
                androidx.compose.ui.viewinterop.AndroidView(factory = { app.vocr.capture.glassChip(it, "Auto-scroll ON", android.graphics.Color.parseColor("#D2B08A")) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { app.vocr.capture.glassChip(it, "Add old recordings", android.graphics.Color.parseColor("#25262B")) })
                androidx.compose.ui.viewinterop.AndroidView(factory = { app.vocr.capture.glassChip(it, "New recording", android.graphics.Color.parseColor("#D2B08A")) })
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        half(Brush.linearGradient(listOf(Color(0xFF0C0D10), Color(0xFF2A2B31), Color(0xFF4A3A2C))), Modifier.weight(1f).fillMaxWidth())
        half(Brush.linearGradient(listOf(Color(0xFFF4F1EA), Color(0xFFD8E4F2), Color(0xFFFFFFFF))), Modifier.weight(1f).fillMaxWidth())
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
    val res = result
    if (res != null) { Strips(res, (ctx as? android.app.Activity)?.intent?.getBooleanExtra("read", false) == true) { result = null }; return }
    Column(
        Modifier.fillMaxSize().scrollOnBackdrop().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Spacer(Modifier.height(16.dp))
                Wordmark(44.sp)
                Text("If you can scroll it, you can save it.", color = Muted, fontSize = 16.sp)
            }
            AddButton(JobQueue.activeCount()) { addVideos() }
        }
        Spacer(Modifier.height(12.dp))
        QueueList()
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(28.dp)).padding(24.dp)) {
            Text("\u25B6", color = Lilac, fontSize = 32.sp)
            Spacer(Modifier.height(12.dp))
            Text(picked?.name ?: "Pick screen recordings", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = DisplayFont)
            Spacer(Modifier.height(4.dp))
            Text(
                picked?.let { "${it.seconds / 60}m ${it.seconds % 60}s  -  ${it.w}x${it.h}" } ?: "OCR runs on your phone. Share only when you choose.",
                color = Muted, fontSize = 14.sp
            )
            val pr = progress
            if (pr != null) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { pr.done / pr.total.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth(), color = Violet, trackColor = Ink)
                Spacer(Modifier.height(6.dp))
                val eta = if (pr.etaS >= 0) "  ~${pr.etaS / 60}m ${pr.etaS % 60}s left" else ""
            Text("${pr.stage}  ${pr.done}/${pr.total}$eta  ${pr.lines} lines", color = Muted, fontSize = 13.sp)
            if (pr.live.isNotEmpty()) Text(pr.live.joinToString("\n"), color = Lilac, fontSize = 12.sp, maxLines = 6, modifier = Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(8.dp)).padding(8.dp))
            }
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Err, fontSize = 13.sp) }
            Spacer(Modifier.height(20.dp))
            val p = picked
            if (p != null && progress == null) {
                Button(
                    onClick = { start(p) },
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = OnBronze)
                ) { Text("Read this recording", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = { addVideos() },
                enabled = progress == null,
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = if (picked == null) ButtonDefaults.buttonColors(containerColor = Violet, contentColor = OnBronze)
                         else ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain)
            ) { Text("Choose videos", fontSize = 14.sp, maxLines = 1, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { ctx.startActivity(android.content.Intent(ctx, SavedActivity::class.java)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain)
        ) { Text("Saved files", fontSize = 14.sp) }
        Spacer(Modifier.height(10.dp))
        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        Button(
            onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 33) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                if (!android.provider.Settings.canDrawOverlays(ctx)) {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)))
                } else {
                    ctx.startForegroundService(android.content.Intent(ctx, app.vocr.capture.CaptureService::class.java).setAction(app.vocr.capture.CaptureService.ACTION_WIDGET))
                    (ctx as? android.app.Activity)?.moveTaskToBack(true)
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain)
        ) { Text(if (android.provider.Settings.canDrawOverlays(ctx)) "Floating recorder" else "Floating recorder (allow overlay)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(10.dp))
        var showSettings by remember { mutableStateOf(false) }
        Button(
            onClick = { showSettings = !showSettings },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain)
        ) { Text(if (showSettings) "Settings  \u25B2" else "Settings  \u25BC", fontSize = 14.sp) }
        if (showSettings) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(20.dp)).padding(16.dp)) {
                val asReady = app.vocr.capture.ScrollService.instance != null
                val asStopped = !asReady && app.vocr.capture.ScrollService.enabledInSettings(ctx)
                // A status row, not a button: ready = tick, no pressed state; otherwise a link row that opens Accessibility settings.
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (asReady) Modifier else Modifier.clickable { showSettings = false; DisclosureActivity.openAccessibility(ctx) }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-scroll", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (asReady) Text("\u2713 Ready", color = Success, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    else Text(if (asStopped) "ON but stopped - switch it off and on  \u203A" else "Turn on in Accessibility  \u203A", color = Lilac, fontSize = 13.sp)
                }
                Spacer(Modifier.height(14.dp))
                val view = androidx.compose.ui.platform.LocalView.current
                var speed by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getInt("scrollspeed", 10).toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-scroll speed", color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(speed.toInt().toString() + if (speed.toInt() == 10) "  (default)" else "", color = Lilac, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    modifier = Modifier.semantics { stateDescription = "${speed.toInt()} of 10" },
                    value = speed, onValueChange = { val n = Math.round(it).toFloat(); if (n != speed) Haptics.detent(view); speed = n },
                    onValueChangeFinished = { app.vocr.capture.CaptureService.prefs(ctx).edit().putInt("scrollspeed", speed.toInt()).apply() },
                    valueRange = 0f..10f, steps = 9,
                    colors = SliderDefaults.colors(thumbColor = Violet, activeTrackColor = Violet, inactiveTrackColor = TrackOff, activeTickColor = OnBronze, inactiveTickColor = TickOff)
                )
                Row { Text("0 slowest", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); Text("10 = best pace", color = Muted, fontSize = 11.sp) }
                Spacer(Modifier.height(4.dp))
                Text(
                    "10 is the best working pace for the text reader. Lower numbers scroll slower so you can read along while it records. 1 is a very slow, smooth crawl. 0 stops the scrolling completely (the recording keeps running).",
                    color = Muted, fontSize = 12.sp, lineHeight = 17.sp
                )
                Spacer(Modifier.height(14.dp))
                var snd by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("sound", true)) }
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = snd, onCheckedChange = { snd = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("sound", it).apply(); app.vocr.core.Sfx.refresh(ctx) })
                    Spacer(Modifier.width(8.dp))
                    Text("Sound (auto-scroll and end cue)", color = Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                var maxRows by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getInt("max_rows", app.vocr.capture.LongShotSession.MAX_ROWS)) }
                Text("Scroll capture size limit (stops at about ${maxRows / app.vocr.capture.LongShotSession.PART_H} parts)", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf(40000 to "40k", 80000 to "80k", 120000 to "120k", 200000 to "200k").forEach { (v, label) ->
                        Button(onClick = { maxRows = v; app.vocr.capture.CaptureService.prefs(ctx).edit().putInt("max_rows", v).apply() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (maxRows == v) Violet else Btn2, contentColor = if (maxRows == v) OnBronze else TextMain),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(label, fontSize = 13.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                var light by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("lightcapture", false)) }
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = light, onCheckedChange = { light = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("lightcapture", it).apply() })
                    Spacer(Modifier.width(8.dp))
                    Text("Lighter capture (30 fps, 5 Mbps)", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Beta", color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Btn2).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Spacer(Modifier.height(6.dp))
                var hap by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("haptics", true)) }
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = hap, onCheckedChange = { hap = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("haptics", it).apply() })
                    Spacer(Modifier.width(8.dp))
                    Text("Soft haptics while auto-scroll runs", color = Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("Theme", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                ThemeSegmented(paper = Pal.paper, onPick = { Pal.set(ctx, it) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                if (app.vocr.capture.Cover.AVAILABLE) {   // Cover is parked for further development
                var cov by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("cover", false)) }
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = cov, onCheckedChange = { cov = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("cover", it).apply() })
                    Spacer(Modifier.width(8.dp))
                    Text("Cover: hide a region (e.g. a playing video) in recordings. Set it from the bubble: long-press, Cover", color = Muted, fontSize = 13.sp)
                }
                var covStrip by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("cover_strip", true)) }
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = covStrip, onCheckedChange = { covStrip = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("cover_strip", it).apply() })
                    Spacer(Modifier.width(8.dp))
                    Text("Cover also hides the status bar strip (clock, notifications)", color = Muted, fontSize = 13.sp)
                }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { ctx.startActivity(android.content.Intent(ctx, ManualActivity::class.java)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain)
        ) { Text("User manual (how to use)", fontSize = 14.sp) }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { ctx.startActivity(android.content.Intent(ctx, AboutActivity::class.java)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain)
        ) { Text("About and contact", fontSize = 14.sp) }
        Spacer(Modifier.height(6.dp))
        Text("v" + (runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?") + "", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
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
                val foot = app.vocr.core.RunStats.footer(res, rr)
                val sv = app.vocr.core.Saver.save(ctx, rr.text.trimEnd() + "\n\n" + foot)
                read = rr.copy(savedTitle = sv.title, savedOk = sv.uri != null, detail = rr.detail + "\n\n" + foot)
            }
            catch (e: Throwable) { err = (e.message ?: e.javaClass.simpleName) }
            reading = null
        }.start()
    }
    LaunchedEffect(Unit) { if (autoRead && read == null && reading == null) startRead() }
    val rd = read
    if (rd != null) { TextScreen(rd) { read = null }; return }
    Column(Modifier.fillMaxSize().scrollOnBackdrop().systemBarsPadding().padding(16.dp)) {
        Text("Kept ${res.strips.size} strips", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFamily = DisplayFont)
        Text(
            "${res.seconds}s video, ${res.framesTotal} frames read, ${res.dup} skipped (dup/accumulating), ${res.full} full, ${res.cropped} cropped. " +
                "Took ${res.ms / 1000.0}s  ${res.timing}",
            color = Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain)) { Text("Back") }
            Button(
                enabled = reading == null,
                onClick = { startRead() },
                colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = OnBronze)
            ) { Text("Read text") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = fast, onCheckedChange = { fast = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("fast", it).apply() }, enabled = reading == null)
            Spacer(Modifier.width(8.dp))
            Text("Fast mode (about 25% quicker, a little less accurate)", color = Muted, fontSize = 12.sp)
        }
        reading?.let { pr ->
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { pr.done / pr.total.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth(), color = Violet, trackColor = Card)
            Text("${pr.stage}  ${pr.done}/${pr.total}", color = Muted, fontSize = 13.sp)
        }
        err?.let { Text(it, color = Err, fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun TextScreen(r: ReadResult, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().scrollOnBackdrop().systemBarsPadding().padding(16.dp)) {
        Text("Text from ${r.strips} strips", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFamily = DisplayFont)
        Text("${r.rawLines} lines read, ${r.mergedLines} after removing overlap. Took ${r.ms / 1000.0}s  (${r.detail})", color = Muted, fontSize = 13.sp)
        if (r.savedTitle.isNotEmpty())
            Text(if (r.savedOk) "Saved to Downloads/ScrollRSI/${r.savedTitle}.txt" else "Could not save the file (use Copy or Share)", color = Success, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain)) { Text("Back") }
            Button(onClick = {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ScrollRSI", r.text))
            }, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = OnBronze)) { Text("Copy") }
            Button(onClick = {
                // Share as an attached .txt (not pasted text), so chat apps receive a file.
                val name = (r.savedTitle.ifEmpty { "ScrollRSI" }).replace(Regex("[^A-Za-z0-9 ._-]"), "_") + ".txt"
                val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = java.io.File(dir, name).apply { writeText(r.text) }
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".share", f)
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri(name, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(android.content.Intent.createChooser(i, "Share text file"))
            }, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = OnBronze)) { Text("Share file") }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(r.text, color = TextMain, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).glassPanel(RoundedCornerShape(12.dp)).padding(12.dp))
        }
    }
}

/** Circled plus, top right: opens the picker and puts the chosen videos on the queue. Badge = jobs waiting or running. */
@Composable
fun AddButton(count: Int, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).glassPanel(androidx.compose.foundation.shape.CircleShape).hapticClickable(onClick), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(34.dp)) {
            val c = center; val r = size.minDimension / 2
            drawCircle(TextMain, r - 1.5f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            val a = r * 0.45f
            drawLine(TextMain, androidx.compose.ui.geometry.Offset(c.x - a, c.y), androidx.compose.ui.geometry.Offset(c.x + a, c.y), strokeWidth = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(TextMain, androidx.compose.ui.geometry.Offset(c.x, c.y - a), androidx.compose.ui.geometry.Offset(c.x, c.y + a), strokeWidth = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        if (count > 0) Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Violet), contentAlignment = Alignment.Center) {
            Text(count.toString(), color = OnBronze, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun QueueList() {
    val ctx = LocalContext.current
    val jobs = JobQueue.jobs
    if (jobs.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).glassPanel(RoundedCornerShape(20.dp)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Queue", modifier = Modifier.weight(1f))
            if (jobs.any { it.state >= 2 }) Text("Clear finished", color = Lilac, fontSize = 13.sp, modifier = Modifier.hapticClickable { JobQueue.clearFinished() })
        }
        // Display order only (run order stays first-in-first-out): the running job first, then waiting jobs, then finished ones newest first.
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.runtime.LaunchedEffect(jobs.size, jobs.count { it.state <= 1 }) { listState.scrollToItem(0) }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(jobs.toList().sortedWith(compareBy({ if (it.state == 1) 0 else if (it.state == 0) 1 else 2 }, { if (it.state >= 2) -it.id else it.id })), key = { it.id }) { j ->
                Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(12.dp), Ink).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (j.state == 2 && j.title.isNotEmpty()) j.title else j.label, color = TextMain, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        if (j.state <= 1) Text("Cancel", color = Err, fontSize = 13.sp, modifier = Modifier.hapticClickable { JobQueue.cancel(j) }.padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                    when (j.state) {
                        0 -> Text("Waiting", color = Muted, fontSize = 12.sp)
                        1 -> { LinearProgressIndicator(progress = { j.frac }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Violet, trackColor = Card); Text(j.stage, color = Muted, fontSize = 12.sp) }
                        2 -> {
                            Text("${j.lines} lines in ${j.secs}s - saved to Downloads/ScrollRSI", color = Success, fontSize = 12.sp)
                            val u = j.result
                            if (u != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                                Text("Open", color = Lilac, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.hapticClickable {
                                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(u, "text/plain").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                })
                                Text("Share file", color = Lilac, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.hapticClickable {
                                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_STREAM, u)
                                        clipData = android.content.ClipData.newRawUri(j.title, u)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    ctx.startActivity(android.content.Intent.createChooser(i, "Share text file"))
                                })
                            }
                        }
                        else -> Text(j.stage, color = if (j.state == 3) Err else Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Opens Downloads/ScrollRSI in the system Files app; falls back to the Downloads view if the folder link is not handled. */
fun openFolder(ctx: android.content.Context) {
    val flags = if (ctx is android.app.Activity) 0 else android.content.Intent.FLAG_ACTIVITY_NEW_TASK   // same task when launched from an Activity, so Back returns here
    val dir = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FScrollRSI")
    val tries = listOf(
        android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(dir, "vnd.android.document/directory").addFlags(flags),
        android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(dir, "resource/folder").addFlags(flags),
        android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(flags)
    )
    for (i in tries) { if (runCatching { ctx.startActivity(i) }.isSuccess) return }
    android.widget.Toast.makeText(ctx, "Files are in Downloads/ScrollRSI", android.widget.Toast.LENGTH_LONG).show()
}
