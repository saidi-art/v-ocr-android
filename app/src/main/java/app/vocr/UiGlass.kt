package app.vocr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Glass look for every screen: translucent gradient body, a soft highlight on the top part, a light rim. (No real blur: Android cannot blur what is behind an app window reliably.) */
val GlassBackdrop: Brush = Brush.linearGradient(listOf(Color(0xFF0B0E17), Color(0xFF1C2B52), Color(0xFF2E1A5C)))

fun Modifier.glassPanel(shape: Shape = RoundedCornerShape(20.dp), tint: Color = Card, strong: Boolean = false): Modifier {
    val up = lerp(tint, Color.White, 0.16f).copy(alpha = if (strong) 0.92f else 0.74f)
    val dn = lerp(tint, Color.Black, 0.18f).copy(alpha = if (strong) 0.88f else 0.66f)
    return this.clip(shape)
        .background(Brush.verticalGradient(listOf(up, dn)), shape)
        .drawWithContent {
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.Transparent), 0f, size.height * 0.5f), size = Size(size.width, size.height * 0.5f))
            drawContent()
        }
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.08f))), shape)
}

/** Drop-in replacement for the Material button with the glass look, a press-in scale and a haptic tick. */
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
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(24.dp)
    val tint = if (enabled) colors.containerColor else Color(0xFF232A45).copy(alpha = 0.5f)
    androidx.compose.material3.Button(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        modifier = modifier.scale(if (pressed) 0.96f else 1f).glassPanel(shape, tint, strong = true),
        enabled = enabled, shape = shape, interactionSource = src,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = colors.contentColor, disabledContainerColor = Color.Transparent, disabledContentColor = Color.White.copy(alpha = 0.4f)),
        content = content
    )
}
