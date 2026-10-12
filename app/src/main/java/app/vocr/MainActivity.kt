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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.vocr.core.*
import app.vocr.ui.*
import dev.chrisbanes.haze.HazeState
import androidx.compose.foundation.clickable
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
        setContent { app.vocr.ui.VOcrTheme { Home() } }
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


private fun openOverlayOrWidget(ctx: android.content.Context) {
    if (!android.provider.Settings.canDrawOverlays(ctx)) {
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } else {
        ctx.startForegroundService(android.content.Intent(ctx, app.vocr.capture.CaptureService::class.java).setAction(app.vocr.capture.CaptureService.ACTION_WIDGET))
        (ctx as? android.app.Activity)?.moveTaskToBack(true)
    }
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
    if (res != null) { Strips(res, (ctx as? android.app.Activity)?.intent?.getBooleanExtra("read", false) == true) { result = null }; return }
    val haze = remember { HazeState() }
    val pr = progress
    GlassBackdrop(haze, orb = {
        Blob(VColor.VioletGlow, 360.dp, Modifier.align(Alignment.CenterEnd).offset(x = 120.dp, y = (-60).dp))
        Blob(Color(0x333B6BFF), 280.dp, Modifier.align(Alignment.BottomStart).offset(x = (-100).dp, y = 40.dp))
        Orb(170.dp, progress = pr?.let { it.done / it.total.coerceAtLeast(1).toFloat() }, active = pr != null, modifier = Modifier.align(Alignment.CenterEnd).offset(x = 60.dp, y = (-90).dp))
    }) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(VSpace.l)) {
            Spacer(Modifier.height(24.dp))
            Row {
                Text("V-", style = VType.Wordmark, color = VColor.Text)
                Text("OCR", style = VType.Wordmark, color = VColor.Violet)
            }
            Text("If you can scroll it, you can save it.", style = VType.Body, color = VColor.TextDim, modifier = Modifier.widthIn(max = 220.dp))
            Spacer(Modifier.height(300.dp))
            GlassCard(haze, Modifier.fillMaxWidth()) {
                Text(picked?.name ?: "Pick a screen recording", style = VType.Title, color = VColor.Text)
                Spacer(Modifier.height(4.dp))
                Text(
                    picked?.let { "${it.seconds / 60}m ${it.seconds % 60}s  \u00B7  ${it.w}x${it.h}" } ?: "Free, offline and private. Nothing leaves your phone.",
                    style = VType.Body, color = VColor.TextDim
                )
                if (pr != null) {
                    Spacer(Modifier.height(12.dp))
                    val eta = if (pr.etaS >= 0) "  \u00B7  ~${pr.etaS / 60}m ${pr.etaS % 60}s left" else ""
                    Text("${pr.stage}  ${pr.done}/${pr.total}$eta", style = VType.Caption, color = VColor.Lilac)
                }
                error?.let { Spacer(Modifier.height(8.dp)); StatusPill("Couldn't read this video: $it", VColor.Ember) }
                Spacer(Modifier.height(16.dp))
                val p = picked
                if (p != null && progress == null) {
                    VButton("Extract strips", { start(p) }, Modifier.fillMaxWidth(), primary = true)
                    Spacer(Modifier.height(10.dp))
                }
                VButton(if (picked == null) "Choose video" else "Choose another",
                    { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    Modifier.fillMaxWidth(), primary = picked == null, enabled = progress == null)
            }
            Spacer(Modifier.height(12.dp))
            val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            VButton(if (android.provider.Settings.canDrawOverlays(ctx)) "Floating recorder" else "Floating recorder (allow overlay)", {
                if (android.os.Build.VERSION.SDK_INT >= 33) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                openOverlayOrWidget(ctx)
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            VButton(if (app.vocr.capture.ScrollService.instance != null) "Auto-scroll: enabled (long-press the bubble)" else "Auto-scroll: turn on in Accessibility", {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            var shots by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("shots", false)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Switch(checked = shots, onCheckedChange = { shots = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("shots", it).apply() })
                Spacer(Modifier.width(8.dp))
                Text("Settings: show extracted screenshots", style = VType.Caption, color = VColor.TextDim)
            }
            Spacer(Modifier.height(6.dp))
            Text("v" + (runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?") + "  \u00B7  strips + on-device text", style = VType.Caption, color = VColor.TextDim, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
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
    val haze = remember { HazeState() }
    val pr = reading
    var details by remember { mutableStateOf(false) }
    var bench by remember { mutableStateOf<List<String>>(emptyList()) }
    var benching by remember { mutableStateOf(false) }
    val showShots = app.vocr.capture.CaptureService.prefs(ctx).getBoolean("shots", false)
    GlassBackdrop(haze, orb = {
        Blob(VColor.VioletGlow, 420.dp, Modifier.align(Alignment.Center).offset(y = (-40).dp))
        if (err != null) Blob(Color(0x44FF7A7A), 300.dp, Modifier.align(Alignment.TopEnd))
    }) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(VSpace.l)) {
            Text("V-OCR", style = VType.Eyebrow, color = VColor.Lilac)
            Text(if (pr != null) "Reading your scroll" else "Kept ${res.strips.size} strips", style = VType.Display, color = VColor.Text)
            Text(if (pr != null) "Everything stays on this phone." else "${res.seconds}s video \u00B7 ${res.framesTotal} frames read", style = VType.Body, color = VColor.TextDim)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                val frac = pr?.let { it.done / it.total.coerceAtLeast(1).toFloat() }
                Box(contentAlignment = Alignment.Center) {
                    Orb(210.dp, progress = frac, active = pr != null)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (pr != null) {
                            val label = if (pr.stage.contains("oad", true) || pr.done == 0) "SCAN" else "${(frac!! * 100).toInt()}%"
                            Text(label, style = VType.Wordmark, color = VColor.Text)
                            Text("strip ${pr.done} of ${pr.total}", style = VType.Caption, color = VColor.TextDim)
                        } else {
                            Text("${res.strips.size}", style = VType.Wordmark, color = VColor.Text)
                            Text("strips ready", style = VType.Caption, color = VColor.TextDim)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (pr != null) {
                    val eta = if (pr.etaS >= 0) "${pr.etaS / 60}:${"%02d".format(pr.etaS % 60)}" else "-"
                    StatRow(listOf("Lines read" to "${pr.lines}", "Strips" to "${pr.done}/${pr.total}", "Time left" to eta), haze)
                } else {
                    GlassCard(haze, Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Fast mode", style = VType.Title, color = VColor.Text)
                                Text("About 25% quicker, a little less accurate", style = VType.Caption, color = VColor.TextDim)
                            }
                            Switch(checked = fast, onCheckedChange = { fast = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("fast", it).apply() })
                        }
                    }
                }
                err?.let { Spacer(Modifier.height(12.dp)); StatusPill("Reading failed: $it", VColor.Ember) }
                Spacer(Modifier.height(4.dp))
                GhostText(if (details) "Details \u25B4" else "Details \u25BE", { details = !details })
                if (details) {
                    GlassCard(haze, Modifier.fillMaxWidth()) {
                        Text("${res.dup} skipped (dup/accumulating), ${res.full} full, ${res.cropped} cropped. Took ${res.ms / 1000.0}s  ${res.timing}", style = VType.Caption, color = VColor.TextDim)
                        Spacer(Modifier.height(10.dp))
                        VButton("Speed test", {
                            benching = true; bench = listOf("Running...")
                            Thread {
                                val acc = ArrayList<String>()
                                try { Reader.bench(ctx, res.strips) { acc.add(it); bench = acc.toList() } } catch (e: Throwable) { acc.add("failed: " + (e.message ?: e.javaClass.simpleName)); bench = acc.toList() }
                                benching = false
                            }.start()
                        }, enabled = pr == null && !benching)
                        if (bench.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(bench.joinToString("\n"), style = VType.Mono, color = VColor.Lilac) }
                    }
                }
                if (!showShots) Text("Screenshots hidden (turn on \"show extracted screenshots\" on the home screen).", style = VType.Caption, color = VColor.TextDim, modifier = Modifier.padding(top = 8.dp))
                if (showShots) {
                    Spacer(Modifier.height(8.dp))
                    res.strips.forEach { s ->
                        val bm = remember(s.file) { BitmapFactory.decodeFile(s.file.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                        Column(Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(VColor.GlassFill).padding(8.dp)) {
                            Text("${s.kind}  t=${"%.1f".format(s.t)}s  ${bm.width * 2}x${bm.height * 2}", style = VType.Caption, color = VColor.Lilac)
                            Image(bm.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (pr == null) {
                VButton("Read text", { startRead() }, Modifier.fillMaxWidth(), primary = true)
                Spacer(Modifier.height(10.dp))
            }
            VButton(if (pr != null) "Back (keeps reading)" else "Back", onBack, Modifier.fillMaxWidth(), enabled = pr == null)
        }
    }
}

@Composable
fun TextScreen(r: ReadResult, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haze = remember { HazeState() }
    var details by remember { mutableStateOf(false) }
    GlassBackdrop(haze, orb = {
        Blob(Color(0x447DFFB0), 300.dp, Modifier.align(Alignment.TopEnd).offset(x = 100.dp, y = 20.dp))
        Blob(VColor.VioletGlow, 380.dp, Modifier.align(Alignment.BottomStart).offset(x = (-120).dp, y = 60.dp))
    }) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(VSpace.l)) {
            Text("V-OCR", style = VType.Eyebrow, color = VColor.Lilac)
            Text(if (r.savedOk) "Saved." else "Done.", style = VType.Display, color = VColor.Text)
            if (r.savedTitle.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                if (r.savedOk) StatusPill("Downloads/V-OCR/${r.savedTitle}.txt", VColor.Mint)
                else StatusPill("Could not save the file (use Copy or Share)", VColor.Ember)
            }
            Spacer(Modifier.height(6.dp))
            Text("${r.mergedLines} lines after removing overlap \u00B7 ${"%.0f".format(r.ms / 1000.0)} s \u00B7 on this phone", style = VType.Caption, color = VColor.TextDim)
            GhostText(if (details) "Details \u25B4" else "Details \u25BE", { details = !details })
            if (details) Text("${r.strips} strips, ${r.rawLines} lines read, ${r.mergedLines} after overlap removal. Took ${r.ms / 1000.0}s  (${r.detail})", style = VType.Caption, color = VColor.TextDim)
            Spacer(Modifier.height(8.dp))
            GlassCard(haze, Modifier.fillMaxWidth().weight(1f)) {
                Text("PREVIEW", style = VType.Eyebrow, color = VColor.TextDim)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(r.text, style = VType.Body, color = VColor.Text, modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VButton("Copy", {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("V-OCR", r.text))
                }, Modifier.weight(1f), primary = true)
                VButton("Share", {
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, r.text) }
                    ctx.startActivity(android.content.Intent.createChooser(i, "Share text"))
                }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            VButton("Back", onBack, Modifier.fillMaxWidth())
        }
    }
}
