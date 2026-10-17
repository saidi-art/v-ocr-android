package app.vocr

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings as bento cards. Every control is the same one the old Settings list had, grouped by purpose. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScrollOnTheme() { SettingsBento { finish() } } }
    }
}

@Composable
fun Tile(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.glassPanel(RoundedCornerShape(24.dp)).let { if (onClick != null) it.hapticClickable(onClick) else it }.padding(16.dp), content = content)
}

@Composable
private fun SettingsBento(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().scrollOnBackdrop().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain)) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text("Settings", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFamily = DisplayFont)
        }
        Tile(Modifier.fillMaxWidth()) { AutoScrollSettings() }
        Tile(Modifier.fillMaxWidth()) { SavingSettings() }
        Tile(Modifier.fillMaxWidth()) { CaptureSettings() }
        Tile(Modifier.fillMaxWidth()) { LookSettings() }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionTitle(t: String) { Label(t); Spacer(Modifier.height(8.dp)) }

@Composable
private fun ColumnScope.AutoScrollSettings() {
    val ctx = LocalContext.current
    SectionTitle("AUTO-SCROLL")
                val asReady = app.vocr.capture.ScrollService.instance != null
                val asStopped = !asReady && app.vocr.capture.ScrollService.enabledInSettings(ctx)
                // A status row, not a button: ready = tick, no pressed state; otherwise a link row that opens Accessibility settings.
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (asReady) Modifier else Modifier.clickable { DisclosureActivity.openAccessibility(ctx) }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-scroll", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (asReady) Text("\u2713 Ready", color = Success, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    else Text(if (asStopped) "ON but stopped - switch it off and on  \u203A" else "Turn on in Accessibility  \u203A", color = Lilac, fontSize = 13.sp)
                }
                if (!asReady && DisclosureActivity.sideloaded(ctx)) Text(DisclosureActivity.RESTRICTED_HINT, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
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
                Spacer(Modifier.height(6.dp))
                var snd by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("sound", true)) }
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = snd, onCheckedChange = { snd = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("sound", it).apply(); app.vocr.core.Sfx.refresh(ctx) })
                    Spacer(Modifier.width(8.dp))
                    Text("Sound (auto-scroll and end cue)", color = Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                var hap by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("haptics", true)) }
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = hap, onCheckedChange = { hap = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("haptics", it).apply() })
                    Spacer(Modifier.width(8.dp))
                    Text("Soft haptics while auto-scroll runs", color = Muted, fontSize = 13.sp)
                }
}

@Composable
private fun ColumnScope.SavingSettings() {
    val ctx = LocalContext.current
    SectionTitle("SAVING")
                var saveVid by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("save_video", true)) }
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = saveVid, onCheckedChange = { saveVid = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("save_video", it).apply() })
                    Spacer(Modifier.width(8.dp))
                    Text("Also save the screen recording video next to its text (large files)", color = Muted, fontSize = 13.sp)
                }
                var dbg by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("debug_frames", false)) }
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = dbg, onCheckedChange = { dbg = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("debug_frames", it).apply() })
                    Spacer(Modifier.width(8.dp))
                    Text("Save every Scroll capture frame for debugging. Contains your screen content.", color = Muted, fontSize = 13.sp)
                }
                var maxRows by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getInt("max_rows", app.vocr.capture.LongShotSession.MAX_ROWS)) }
                Text("Scroll capture size limit (stops at about ${maxRows / app.vocr.capture.LongShotSession.PART_H} parts)", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf(40000 to "40k", 80000 to "80k", 120000 to "120k", 200000 to "200k").forEach { (v, label) ->
                        Button(onClick = { maxRows = v; app.vocr.capture.CaptureService.prefs(ctx).edit().putInt("max_rows", v).apply() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (maxRows == v) Violet else Btn2, contentColor = if (maxRows == v) OnBronze else TextMain),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(label, fontSize = 13.sp) }
                    }
                }
}

@Composable
private fun ColumnScope.CaptureSettings() {
    val ctx = LocalContext.current
    SectionTitle("CAPTURE")
    var shotText by remember { mutableStateOf(app.vocr.capture.CaptureService.prefs(ctx).getBoolean("shot_text", true)) }
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = shotText, onCheckedChange = { shotText = it; app.vocr.capture.CaptureService.prefs(ctx).edit().putBoolean("shot_text", it).apply() })
        Spacer(Modifier.width(8.dp))
        Text("Shot text: also save the text of every screenshot and Scroll capture", color = Muted, fontSize = 13.sp)
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
}

@Composable
private fun ColumnScope.LookSettings() {
    val ctx = LocalContext.current
    SectionTitle("LOOK")
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
}
