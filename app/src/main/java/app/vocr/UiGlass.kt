package app.vocr

import androidx.compose.foundation.clickable
import androidx.compose.ui.composed
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/** Kept for the screens that still name it. */
val GlassBackdrop: Brush = Brush.verticalGradient(listOf(Ground, Ground))

/**
 * ScrOllOn glass: translucent fill over the soft chrome backdrop (solid fallback under API 31, per the sheet), a metallic edge
 * (bright top, dark bottom, diagonal sheen) and a faint top highlight. [tint] darkens it for inner items.
 */
fun Modifier.glassPanel(shape: Shape = RoundedCornerShape(24.dp), tint: Color = Card, strong: Boolean = false): Modifier {
    val blurOk = Build.VERSION.SDK_INT >= 31
    val paper = Pal.paper
    val hi = Color.White; val lo = if (paper) Color(0xFF0C0C0C) else Color.Black
    val base = if (paper) (if (blurOk) Color.White.copy(alpha = if (tint == Ink) 0.60f else 0.80f) else Color(0xEBF9F6F0))
               else if (blurOk) (if (tint == Ink) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = if (strong) 0.09f else 0.05f))
               else Color(0xE0141519)
    return this.clip(shape)
        .background(base, shape)
        .drawWithContent {
            drawRect(Brush.verticalGradient(listOf(hi.copy(alpha = if (paper) 0.60f else if (blurOk) 0.07f else 0.04f), Color.Transparent), 0f, size.height * 0.45f), size = Size(size.width, size.height * 0.45f))
            drawContent()
        }
        .border(1.dp, Brush.linearGradient(if (paper) listOf(lo.copy(alpha = 0.16f), lo.copy(alpha = 0.08f), lo.copy(alpha = 0.12f)) else listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.22f)), Offset.Zero, Offset(900f, 900f)), shape)
        .border(1.dp, Brush.verticalGradient(listOf(Color.Transparent, lo.copy(alpha = if (paper) 0.10f else 0.35f))), shape)
}

/** Bronze primary / dark glass secondary button with the sheet's press spring and haptics. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain),
    content: @Composable RowScope.() -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val view = LocalView.current
    val primary = colors.containerColor == Violet
    val shape = RoundedCornerShape(27.dp)
    val sc by animateFloatAsState(if (pressed) 0.97f else 1f, if (pressed) spring(0.80f, 800f) else spring(0.55f, 400f), label = "press")
    androidx.compose.runtime.LaunchedEffect(pressed) { if (pressed && enabled) Haptics.secondary(view) }   // same tick as the floating bubble, on touch-down
    val surface: Modifier = Modifier.bubbleBody(shape, if (primary) Violet else Btn2, pressed, enabled)
    androidx.compose.material3.Button(
        onClick = { if (primary) Haptics.primary(view); onClick() },
        modifier = modifier.scale(sc).then(surface),
        enabled = enabled, shape = shape, interactionSource = src,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = if (primary) OnBronze else TextMain, disabledContainerColor = Color.Transparent, disabledContentColor = TextMain.copy(alpha = 0.4f)),
        content = content
    )
}

/** Switch with the toggle haptic and bronze track. */
@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val view = LocalView.current
    androidx.compose.material3.Switch(
        checked = checked, enabled = enabled, modifier = modifier,
        onCheckedChange = onCheckedChange?.let { f -> { v: Boolean -> Haptics.toggle(view, v); f(v) } },
        colors = SwitchDefaults.colors(checkedTrackColor = Violet, checkedThumbColor = OnBronze, checkedBorderColor = BronzeHi, uncheckedTrackColor = TrackOff, uncheckedThumbColor = Muted, uncheckedBorderColor = TrackOff)
    )
}

/** clickable with the light-press haptic: every tap confirms. */
fun Modifier.hapticClickable(onClick: () -> Unit): Modifier = composed {
    val view = androidx.compose.ui.platform.LocalView.current
    this.clickable { Haptics.secondary(view); onClick() }
}

/** Bubble look for buttons: lit gradient body, bright rim, specular band, drop shadow that shrinks on press (Graphite and Paper). */
fun Modifier.bubbleBody(shape: Shape, base: Color, pressed: Boolean, enabled: Boolean = true): Modifier {
    val paper = Pal.paper
    val up = androidx.compose.ui.graphics.lerp(base, Color.White, if (pressed) 0.08f else if (paper) 0.22f else 0.30f)
    val dn = androidx.compose.ui.graphics.lerp(base, Color.Black, if (pressed) (if (paper) 0.22f else 0.38f) else if (paper) 0.12f else 0.28f)
    val a = if (enabled) 1f else 0.45f
    val shadow = if (paper) Color(0xFF0C0C0C) else Color.Black
    return this
        .shadow(if (pressed) 2.dp else 7.dp, shape, clip = false, ambientColor = shadow, spotColor = shadow)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(up.copy(alpha = a), dn.copy(alpha = a))), shape)
        .drawWithContent {
            drawContent()
        }
        .drawBehind {
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (pressed) 0.12f else if (paper) 0.55f else 0.32f), Color.Transparent), 0f, size.height * 0.5f), size = Size(size.width, size.height * 0.5f))
        }
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (paper) 0.9f else 0.55f), (if (paper) Color(0xFF0C0C0C).copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.30f)))), shape)
}
