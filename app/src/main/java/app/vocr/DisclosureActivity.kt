package app.vocr

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Prominent disclosure for the accessibility service. Shown before the user is sent to Android's Accessibility settings. */
class DisclosureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScrollOnTheme() {
                Disclosure(
                    onAgree = {
                        getSharedPreferences("vocr", MODE_PRIVATE).edit().putBoolean("a11y_ok", true).apply()
                        finish()   // close this screen first (it is noHistory + excluded from recents), then hand over to Settings
                        openAccessibilityStack(this)
                    },
                    onDecline = {
                        app.vocr.capture.CaptureService.prefs(this).edit().putBoolean("autoscroll", false).apply()   // a declined notice leaves auto-scroll visibly off
                        Toast.makeText(this, "Auto-scroll stays off. You can still scroll by hand while recording.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        /** True when the app was not installed by Google Play (a downloaded APK): Android then blocks Accessibility until "Allow restricted settings" is chosen in App info. */
        fun sideloaded(ctx: android.content.Context): Boolean = runCatching {
            if (android.os.Build.VERSION.SDK_INT < 30) return false
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName != "com.android.vending"
        }.getOrDefault(false)

        /** Opens Android's Accessibility page on top of the main Device Settings page, in Settings' own task. Back from Accessibility then lands on Device Settings
         *  (where Apps > ScrollRSI > Allow restricted settings is one tap away), and one more Back returns to ScrollRSI. Falls back to the plain Accessibility page. */
        fun openAccessibilityStack(ctx: android.content.Context) {
            val main = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val acc = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            if (runCatching { ctx.startActivities(arrayOf(main, acc)) }.isSuccess) return
            val plain = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            if (ctx !is android.app.Activity) plain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(plain)
        }

        const val RESTRICTED_HINT = "If Android says \"restricted setting\" or \"App was denied access\": Settings > Apps > ScrollRSI > three dots at the top right > Allow restricted settings, then try again."

        /** Open Accessibility settings, but only after the user has accepted the disclosure; otherwise show it first. */
        fun openAccessibility(ctx: android.content.Context) {
            val ok = ctx.getSharedPreferences("vocr", android.content.Context.MODE_PRIVATE).getBoolean("a11y_ok", false)
            if (ok) { openAccessibilityStack(ctx); return }
            val i = Intent(ctx, DisclosureActivity::class.java)
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}

@Composable
fun Disclosure(onAgree: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxSize().scrollOnBackdrop().systemBarsPadding().padding(16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Auto-scroll needs Accessibility", color = TextMain, fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, fontFamily = DisplayFont)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(20.dp)).padding(18.dp)) {
                Text("What ScrollRSI does with this permission", color = Lilac, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. It performs swipe gestures on your screen to scroll the page for you, only while you have started a recording or a Scroll capture.\n\n" +
                        "2. It takes screenshots of your screen, only for the capture you start. During auto-scroll, a tiny grey screenshot (48 x 96 pixels) is compared with the previous one to find the bottom of the page and is then discarded. For Screenshot and Scroll capture, full-resolution screenshots are taken, held temporarily on your phone while the long image is built, and saved to Download/ScrollRSI as the image you asked for.\n\n" +
                        "3. During a Scroll capture it notices that the page scrolled and by how far (scroll events carry numbers only, never content), so the parts of the long image line up.",
                    color = TextMain, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(20.dp)).padding(18.dp)) {
                Text("What it does not do", color = Lilac, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "It does not read text, passwords or anything you type through Accessibility, and it takes screenshots only for a capture you start. A screenshot shows whatever is on screen at that moment, including other apps. " +
                        "Nothing is sent anywhere: ScrollRSI has no internet permission and reads text on your phone only.",
                    color = TextMain, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "This is optional. ScrollRSI works without it; you just scroll by hand while recording. " +
                    "If you agree, Android's Accessibility settings open next. Turn on \"ScrollRSI\" there. You can turn it off at any time.\n\n" +
                    "If Android refuses (\"App was denied access\", or ScrollRSI is greyed out as \"Controlled by restricted setting\"): tap Close, go to Settings > Apps > ScrollRSI > three dots at the top right > Allow restricted settings, then turn it on in Accessibility again. The manual has the full steps.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAgree, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = OnBronze)
        ) { Text("I agree - open Accessibility settings", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onDecline, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Btn2, contentColor = TextMain)
        ) { Text("No thanks", fontSize = 14.sp) }
    }
}
