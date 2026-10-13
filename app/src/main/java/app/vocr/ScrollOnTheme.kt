package app.vocr

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// ScrOllOn look: graphite ground, chrome and bronze. Tokens follow the design sheet (v2).
val Ground = Color(0xFF0C0D10)
val GroundGlow = Color(0xFF33343A)
val TextBody = Color(0xFFD9D6D0)
val BronzeHi = Color(0xFFE3C7A2)
val BronzeLo = Color(0xFFB08558)
val BronzeDeep = Color(0xFF9A7650)
val OnBronze = Color(0xFF17120C)
val Success = Color(0xFF86D0A0)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, w: Int, italic: Boolean = false) =
    Font(res, FontWeight(w), if (italic) FontStyle.Italic else FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Manrope for labels and body. */
val BodyFont = FontFamily(variable(R.font.manrope, 400), variable(R.font.manrope, 500), variable(R.font.manrope, 600), variable(R.font.manrope, 700), variable(R.font.manrope, 800))
/** Cormorant Garamond for display type (upright and italic). */
val DisplayFont = FontFamily(variable(R.font.cormorant_garamond, 500), variable(R.font.cormorant_garamond, 600), variable(R.font.cormorant_garamond, 700), variable(R.font.cormorant_garamond_italic, 500, true), variable(R.font.cormorant_garamond_italic, 600, true))

private fun st(size: Int, w: Int = 500, lh: Float = 1.5f) = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight(w), fontSize = size.sp, lineHeight = (size * lh).sp, color = Color.Unspecified)
val ScrollOnTypography = Typography(
    displayLarge = st(57), displayMedium = st(45), displaySmall = st(36),
    headlineLarge = st(32), headlineMedium = st(28), headlineSmall = st(24),
    titleLarge = st(22, 600), titleMedium = st(16, 600), titleSmall = st(14, 600),
    bodyLarge = st(16), bodyMedium = st(14, 500, 1.6f), bodySmall = st(12),
    labelLarge = st(14, 800), labelMedium = st(12, 700), labelSmall = st(11, 800)
)

/** Small tracked uppercase label (bronze by default), per the sheet: Manrope 800, 11 sp, 0.22 em. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Lilac, size: TextUnit = 11.sp) =
    Text(text.uppercase(), modifier = modifier, color = color, fontFamily = BodyFont, fontWeight = FontWeight.ExtraBold, fontSize = size, letterSpacing = (size.value * 0.22f).sp)

/** ScrOllOn wordmark: Cormorant Garamond with the two O's in bronze. */
@Composable
fun Wordmark(size: TextUnit = 44.sp) {
    Row {
        for ((t, c) in listOf("Scr" to Color.White, "O" to BronzeHi, "ll" to Color.White, "O" to BronzeHi, "n" to Color.White))
            Text(t, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = size, color = c)
    }
}

/** Graphite ground with a soft glow at the top and chrome spheres (drawn already soft: no hard edges, so the glass panels over them read as frosted). */
fun Modifier.scrollOnBackdrop(): Modifier = this.drawBehind {
    val w = size.width; val h = size.height
    drawRect(Ground)
    drawRect(Brush.radialGradient(listOf(GroundGlow, Color.Transparent), Offset(w * 0.3f, 0f), w * 1.1f))
    fun sphere(cx: Float, cy: Float, r: Float, a: Float) {
        drawCircle(Brush.radialGradient(listOf(Color(0xFF8A8C93).copy(alpha = a), Color(0xFF3A3B41).copy(alpha = a * 0.8f), Color(0xFF16171B).copy(alpha = a * 0.5f), Color.Transparent), Offset(cx - r * 0.3f, cy - r * 0.35f), r * 1.15f), r * 1.15f, Offset(cx, cy))
        drawCircle(Brush.radialGradient(listOf(Color.Transparent, BronzeLo.copy(alpha = a * 0.35f), Color.Transparent), Offset(cx, cy), r * 1.2f), r * 1.2f, Offset(cx, cy))
    }
    sphere(w * 0.95f, h * 0.22f, w * 0.16f, 0.9f)
    sphere(w * 0.02f, h * 0.60f, w * 0.20f, 0.8f)
    sphere(w * 0.88f, h * 0.90f, w * 0.22f, 0.8f)
}
