package app.vocr

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
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
/** Two palettes: Graphite (the original) and Paper (ink on cream, tokens from the production deck, "Paper tokens"). Palette only. */
object Pal {
    var paper by mutableStateOf(false)
    fun load(ctx: android.content.Context) { paper = ctx.getSharedPreferences("vocr", android.content.Context.MODE_PRIVATE).getBoolean("paper", false) }
    fun set(ctx: android.content.Context, v: Boolean) { ctx.getSharedPreferences("vocr", android.content.Context.MODE_PRIVATE).edit().putBoolean("paper", v).apply(); paper = v }
}
private fun pick(g: Long, p: Long) = if (Pal.paper) Color(p) else Color(g)
val Ground: Color get() = pick(0xFF0C0D10, 0xFFF9F6F0)
val GroundGlow: Color get() = pick(0xFF33343A, 0xFFFDFBF7)
val TextBody: Color get() = pick(0xFFD9D6D0, 0xD1191919)
val BronzeHi: Color get() = pick(0xFFE3C7A2, 0xFF4A4A4A)
val BronzeLo: Color get() = pick(0xFFB08558, 0xFF111111)
val BronzeDeep: Color get() = pick(0xFF9A7650, 0xFF191919)
val OnBronze: Color get() = pick(0xFF17120C, 0xFFF9F6F0)
val Success: Color get() = pick(0xFF86D0A0, 0xFF3F7556)
/** Main text (white on Graphite, ink on Paper). */
val TextMain: Color get() = pick(0xFFFFFFFF, 0xFF191919)
/** Secondary button fill. */
val Btn2: Color get() = pick(0xFF25262B, 0xFFE9E3D6)
val Err: Color get() = pick(0xFFFF7A7A, 0xFFB3261E)
val TrackOff: Color get() = pick(0x33FFFFFF, 0x1F191919)
val TickOff: Color get() = pick(0x55FFFFFF, 0x40191919)
val WordO: Color get() = pick(0xFFE3C7A2, 0xFF191919)

/** Material scheme for the active palette. */
@Composable
fun scrollScheme() = if (Pal.paper) androidx.compose.material3.lightColorScheme(primary = Violet, background = Ink, surface = Card, onBackground = TextMain, onSurface = TextMain)
                     else androidx.compose.material3.darkColorScheme(primary = Violet, background = Ink, surface = Card)

/** Theme wrapper for every screen: palette, typography, window and system bar colors. */
@Composable
fun ScrollOnTheme(content: @Composable () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val bg = Ground.toArgb(); val light = Pal.paper
    androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
            w.statusBarColor = bg; w.navigationBarColor = bg
            val c = androidx.core.view.WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = light; c.isAppearanceLightNavigationBars = light
        }
    }
    androidx.compose.material3.MaterialTheme(colorScheme = scrollScheme(), typography = ScrollOnTypography, content = content)
}

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
        for ((t, c) in listOf("Scr" to TextMain, "O" to WordO, "ll" to TextMain, "O" to WordO, "n" to TextMain))
            Text(t, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = size, color = c)
    }
}

/** Graphite ground with a soft glow at the top and chrome spheres (drawn already soft: no hard edges, so the glass panels over them read as frosted). */
fun Modifier.scrollOnBackdrop(): Modifier = this.drawBehind {
    val w = size.width; val h = size.height
    drawRect(Ground)
    drawRect(Brush.radialGradient(listOf(GroundGlow, Color.Transparent), Offset(w * 0.3f, 0f), w * 1.1f))
    fun sphere(cx: Float, cy: Float, r: Float, a: Float) {
        if (Pal.paper) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFF7A7A7A).copy(alpha = a * 0.5f), Color(0xFF2D2D2D).copy(alpha = a * 0.4f), Color(0xFF191919).copy(alpha = a * 0.3f), Color.Transparent), Offset(cx - r * 0.36f, cy - r * 0.48f), r * 1.25f), r * 1.2f, Offset(cx, cy))
            return
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFF8A8C93).copy(alpha = a), Color(0xFF3A3B41).copy(alpha = a * 0.8f), Color(0xFF16171B).copy(alpha = a * 0.5f), Color.Transparent), Offset(cx - r * 0.3f, cy - r * 0.35f), r * 1.15f), r * 1.15f, Offset(cx, cy))
        drawCircle(Brush.radialGradient(listOf(Color.Transparent, BronzeLo.copy(alpha = a * 0.35f), Color.Transparent), Offset(cx, cy), r * 1.2f), r * 1.2f, Offset(cx, cy))
    }
    sphere(w * 0.95f, h * 0.22f, w * 0.16f, 0.9f)
    sphere(w * 0.02f, h * 0.60f, w * 0.20f, 0.8f)
    sphere(w * 0.88f, h * 0.90f, w * 0.22f, 0.8f)
}
