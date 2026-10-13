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
            MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Ink, surface = Card)) {
                Disclosure(
                    onAgree = {
                        getSharedPreferences("vocr", MODE_PRIVATE).edit().putBoolean("a11y_ok", true).apply()
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        finish()
                    },
                    onDecline = {
                        Toast.makeText(this, "Auto-scroll stays off. You can still scroll by hand while recording.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        /** Open Accessibility settings, but only after the user has accepted the disclosure; otherwise show it first. */
        fun openAccessibility(ctx: android.content.Context) {
            val ok = ctx.getSharedPreferences("vocr", android.content.Context.MODE_PRIVATE).getBoolean("a11y_ok", false)
            val i = if (ok) Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) else Intent(ctx, DisclosureActivity::class.java)
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
fun Disclosure(onAgree: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).systemBarsPadding().padding(16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Auto-scroll needs Accessibility", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(18.dp)) {
                Text("What V-OCR does with this permission", color = Lilac, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. It performs swipe gestures on your screen to scroll the page for you while you record.\n\n" +
                        "2. After each swipe it takes a tiny, low-resolution grey screenshot of your screen (48 x 96 pixels, no colour) and compares it with the previous one, to find out when the page stops moving and you have reached the bottom. " +
                        "These pictures are held in memory for a moment and then discarded. They are never saved.",
                    color = Color.White, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(18.dp)) {
                Text("What it does not do", color = Lilac, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "It does not read text, passwords or anything you type, and it does not see other apps' content. " +
                        "Nothing is sent anywhere: V-OCR has no internet permission and reads text on your phone only.",
                    color = Color.White, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "This is optional. V-OCR works without it; you just scroll by hand while recording. " +
                    "If you agree, Android's Accessibility settings open next. Turn on \"V-OCR auto-scroll\" there. You can turn it off at any time.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAgree, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White)
        ) { Text("I agree - open Accessibility settings", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onDecline, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232A45), contentColor = Color.White)
        ) { Text("No thanks", fontSize = 14.sp) }
    }
}
