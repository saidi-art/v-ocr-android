package app.vocr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Obsidian Glass tokens. One accent (Violet), one success (Mint), one danger (Ember). Nothing else is coloured. */
object VColor {
    val Void = Color(0xFF05060B)       // page base, deepest
    val Ink = Color(0xFF0B0E17)        // existing brand navy-black, top of the gradient
    val Night = Color(0xFF111631)      // existing brand, bottom of the gradient
    val GlassFill = Color(0x0AFFFFFF)  // white 4%: translucent tint over a 28dp blur, the backdrop stays visible
    val Sheen = Color(0x1AFFFFFF)      // white 10% to 0 diagonal highlight
    val GlassFillHi = Color(0x14FFFFFF)// white 8%: raised / pressed panel
    val EdgeLit = Color(0x33FFFFFF)    // top-left rim light, white 20%
    val EdgeDim = Color(0x08FFFFFF)    // bottom-right rim, white 3%
    val Violet = Color(0xFF7C5CFF)     // primary action, progress arc, wordmark "OCR"
    val VioletGlow = Color(0x667C5CFF) // 40% halo
    val Lilac = Color(0xFFB7A8FF)      // secondary accent text
    val Text = Color(0xFFF2F4FA)
    val TextDim = Color(0xFF8A93AD)    // existing Muted
    val Mint = Color(0xFF7DFFB0)       // saved only
    val Ember = Color(0xFFFF7A7A)      // errors only
}

object VType {
    // Bundle Inter (variable, OFL) in res/font. Fallback FontFamily.SansSerif is fine.
    val Wordmark = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
    val Display = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = 34.sp)
    val Title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val Caption = TextStyle(fontSize = 12.sp, letterSpacing = 0.4.sp)
    val Eyebrow = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
    val Mono = TextStyle(fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}

object VSpace { val xs = 4.dp; val s = 8.dp; val m = 16.dp; val l = 24.dp; val xl = 40.dp }
object VRadius { val chip = 12.dp; val card = 28.dp; val button = 20.dp }

@Composable
fun VOcrTheme(content: @Composable () -> Unit) {
    // The app is dark only: the obsidian look has no light twin.
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VColor.Violet, onPrimary = Color.White,
            background = VColor.Ink, onBackground = VColor.Text,
            surface = VColor.Ink, onSurface = VColor.Text, error = VColor.Ember
        ),
        content = content
    )
}
