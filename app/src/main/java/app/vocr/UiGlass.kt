package app.vocr

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
    val base = if (blurOk) (if (tint == Ink) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = if (strong) 0.09f else 0.05f))
               else Color(0xE0141519)
    return this.clip(shape)
        .background(base, shape)
        .drawWithContent {
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (blurOk) 0.07f else 0.04f), Color.Transparent), 0f, size.height * 0.45f), size = Size(size.width, size.height * 0.45f))
            drawContent()
        }
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.22f)), Offset.Zero, Offset(900f, 900f)), shape)
        .border(1.dp, Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f))), shape)
}

/** Bronze primary / dark glass secondary button with the sheet's press spring and haptics. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232A45), contentColor = Color.White),
    content: @Composable RowScope.() -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val view = LocalView.current
    val primary = colors.containerColor == Violet
    val shape = RoundedCornerShape(27.dp)
    val sc by animateFloatAsState(if (pressed) 0.97f else 1f, if (pressed) spring(0.80f, 800f) else spring(0.55f, 400f), label = "press")
    val surface: Modifier = if (primary && enabled)
        Modifier.clip(shape).background(Brush.verticalGradient(listOf(BronzeHi, Violet, BronzeLo)), shape)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.25f))), shape)
    else Modifier.glassPanel(shape, Ink, strong = true)
    androidx.compose.material3.Button(
        onClick = { if (primary) Haptics.primary(view) else Haptics.secondary(view); onClick() },
        modifier = modifier.scale(sc).then(surface),
        enabled = enabled, shape = shape, interactionSource = src,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = if (primary) OnBronze else Color.White, disabledContainerColor = Color.Transparent, disabledContentColor = Color.White.copy(alpha = 0.4f)),
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
        colors = SwitchDefaults.colors(checkedTrackColor = Violet, checkedThumbColor = OnBronze, checkedBorderColor = BronzeHi, uncheckedTrackColor = Color(0x33FFFFFF), uncheckedThumbColor = Color(0xFFA9A6A0), uncheckedBorderColor = Color(0x33FFFFFF))
    )
}
