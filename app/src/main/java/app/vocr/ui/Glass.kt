package app.vocr.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle

/**
 * Glass panel. Needs a HazeState wired to the backdrop (see [GlassBackdrop]).
 * Layers, bottom to top: backdrop blur 24dp, white 8% fill, 1dp rim light (bright top-left, dim bottom-right).
 * Haze blurs for real on Android 12+ (RenderEffect). Below that it falls back to the plain fill, which still reads as glass on the dark base.
 */
@Composable
fun GlassCard(
    haze: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(VRadius.card),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(shape)
            .hazeChild(haze, style = HazeStyle(tint = dev.chrisbanes.haze.HazeTint(VColor.GlassFill), blurRadius = 28.dp, noiseFactor = 0.03f))
            .background(Brush.linearGradient(listOf(VColor.Sheen, Color.Transparent)))   // soft top-left sheen over the blur
            .border(1.dp, Brush.linearGradient(listOf(VColor.EdgeLit, VColor.EdgeDim)), shape)
            .padding(VSpace.l),
        content = content
    )
}

/** Full-screen backdrop: navy gradient + the orb. Everything glass sits above this and blurs it. */
@Composable
fun GlassBackdrop(haze: HazeState, modifier: Modifier = Modifier, orb: @Composable BoxScope.() -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(VColor.Ink, VColor.Night, VColor.Void)))) {
        Box(Modifier.fillMaxSize().haze(haze)) { orb() }
        content()
    }
}

/**
 * The signature sphere: black glass with a violet core light.
 * Built from 5 draw layers, no bitmaps: body, violet underglow, specular highlight, rim, slow "breathing" scale.
 * [progress] null = idle; 0..1 draws the progress arc around it (Reading screen).
 */
@Composable
fun Orb(size: Dp, modifier: Modifier = Modifier, progress: Float? = null, active: Boolean = false) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(1f, if (active) 1.035f else 1.015f, infiniteRepeatable(tween(if (active) 1800 else 4200, easing = EaseInOutSine), RepeatMode.Reverse), label = "b")
    val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "s")
    Box(modifier.size(size).graphicsLayer { scaleX = breathe; scaleY = breathe }.drawBehind {
        val r = this.size.minDimension / 2f
        val c = center
        // 1 underglow
        drawCircle(Brush.radialGradient(listOf(VColor.VioletGlow, Color.Transparent), c, r * 1.35f), r * 1.35f, c)
        // 2 body: off-centre radial, near-black with a violet tint low-right
        drawCircle(Brush.radialGradient(
            0f to Color(0xFF3A3F55), 0.35f to Color(0xFF12151F), 0.8f to Color(0xFF05060B), 1f to Color(0xFF1B1440),
            center = Offset(c.x - r * 0.35f, c.y - r * 0.4f), radius = r * 1.5f), r, c)
        // 3 specular: small soft white ellipse upper-left
        drawOval(Brush.radialGradient(listOf(Color(0xCCFFFFFF), Color.Transparent), Offset(c.x - r * 0.38f, c.y - r * 0.45f), r * 0.28f),
            Offset(c.x - r * 0.66f, c.y - r * 0.68f), Size(r * 0.56f, r * 0.4f))
        // 4 rim: lit edge top-left
        drawCircle(Brush.linearGradient(listOf(Color(0x66FFFFFF), Color.Transparent, Color(0x337C5CFF)), Offset(0f, 0f), Offset(this.size.width, this.size.height)), r, c, style = Stroke(1.5.dp.toPx()))
        // 5 progress arc
        if (progress != null) {
            val pad = 6.dp.toPx(); val d = this.size.minDimension - pad * 2
            drawArc(Color(0x22FFFFFF), 0f, 360f, false, Offset(pad, pad), Size(d, d), style = Stroke(3.dp.toPx()))
            drawArc(Brush.sweepGradient(listOf(Color(0x007C5CFF), VColor.Violet), c), -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(pad, pad), Size(d, d), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }.let { if (progress == null && active) it.graphicsLayer { rotationZ = 0f * sweep } else it })
}

/** Primary / secondary button. Spring press (scale 0.96), light haptic on press, violet glow on primary. */
@Composable
fun VButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "press")
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(VRadius.button)
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .then(if (primary) Modifier.drawBehind { drawRoundRect(VColor.VioletGlow, cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()), size = size.copy(width = size.width, height = size.height), topLeft = Offset(0f, 6.dp.toPx())) } else Modifier)
            .clip(shape)
            .background(if (primary) Brush.verticalGradient(listOf(Color(0xFF9378FF), VColor.Violet)) else Brush.verticalGradient(listOf(VColor.GlassFillHi, VColor.GlassFill)))
            .border(1.dp, if (primary) SolidColor(Color(0x33FFFFFF)) else Brush.linearGradient(listOf(VColor.EdgeLit, VColor.EdgeDim)), shape)
            .clickable(src, null, enabled = enabled) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .heightIn(min = 52.dp).padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = VColor.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** Small status pill: Saved (mint), Error (ember), Info (lilac). */
@Composable
fun StatusPill(text: String, tone: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.12f)).border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = tone, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Three-figure stat row for the Reading screen. Raw timing logs live behind a "Details" toggle, not here. */
@Composable
fun StatRow(stats: List<Pair<String, String>>, haze: HazeState, modifier: Modifier = Modifier) {
    GlassCard(haze, modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            stats.forEach { (label, value) ->
                Column { Text(label, style = VType.Caption, color = VColor.TextDim); Text(value, style = VType.Title, color = VColor.Text) }
            }
        }
    }
}

/**
 * Floating recorder bubble (the Compose twin of the overlay view). 56dp glass sphere, progress ring while recording,
 * "SCAN" / % text in the middle. Long-press opens the menu. Tuck-aside leaves a 32dp sliver.
 */
@Composable
fun RecorderBubble(label: String, progress: Float?, recording: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Orb(56.dp, progress = progress, active = recording)
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (recording) {
            val t = rememberInfiniteTransition(label = "rec")
            val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
            Box(Modifier.align(Alignment.TopEnd).size(9.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(VColor.Ember))
        }
    }
}

/** Soft colour light behind the glass. The blur needs something coloured to show. */
@Composable
fun Blob(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).drawBehind { drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center, this.size.minDimension / 2f)) })
}

/** Small ghost text button for tertiary actions ("Details"). */
@Composable
fun GhostText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(text, color = VColor.TextDim, fontSize = 13.sp, modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp))
}
