package app.vocr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Minimal About page: name, version, contact, privacy note. Back returns to Home. */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScrollOnTheme() { About { finish() } } }
    }
}

const val CONTACT_EMAIL = "saidi@kubeai.info"
const val PRIVACY_URL = "https://app.kubeai.info/scrollrsi/privacy/"

@Composable
fun About(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    Column(Modifier.fillMaxSize().scrollOnBackdrop().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain)) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text("About", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFamily = DisplayFont)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(20.dp)).padding(18.dp)) {
                Wordmark(36.sp)
                Spacer(Modifier.height(6.dp))
                Text("If you can scroll it, you can save it.", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Label("VERSION")
                Text(ver, color = TextMain, fontSize = 15.sp)
            }
            Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(20.dp)).padding(18.dp)) {
                Label("PRIVACY")
                Spacer(Modifier.height(6.dp))
                Text("ScrollRSI reads your screen recordings on this phone. Nothing is uploaded and there is no account. Text files are saved in Downloads/ScrollRSI on your device. Auto-scroll, if you turn it on, uses Android's accessibility permission only to swipe the screen and to take tiny in-memory screenshots that detect the end of the page.", color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (!CONTACT_EMAIL.startsWith("__")) Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(20.dp)).padding(18.dp)) {
                Label("CONTACT")
                Spacer(Modifier.height(6.dp))
                Text(CONTACT_EMAIL, color = Lilac, fontSize = 15.sp, modifier = Modifier.hapticClickable {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:$CONTACT_EMAIL"))) }
                })
            }
            Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(20.dp)).padding(18.dp)) {
                Label("PRIVACY POLICY")
                Spacer(Modifier.height(6.dp))
                Text(PRIVACY_URL, color = Lilac, fontSize = 15.sp, modifier = Modifier.hapticClickable {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_URL))) }
                })
            }
        }
    }
}
