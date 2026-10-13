package app.vocr

import android.os.Bundle
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

class ManualActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Ink, surface = Card)) { Manual { finish() } } }
    }
}

private val sections: List<Pair<String, String>> = listOf(
    "What V-OCR does" to
        "V-OCR turns a screen recording of scrolling content (a chat, an article, a document, a web page) into one clean text file. " +
        "It reads English and French, runs fully on your phone, and nothing is uploaded. Lines that appear in several frames are merged, so you get each line once.",
    "Quick start (3 steps)" to
        "1. Tap \"Floating recorder\" on the home screen. The first time, allow \"Display over other apps\" and notifications, then tap it again. A small round bubble appears and V-OCR moves to the background.\n" +
        "2. Open what you want to save. Tap the bubble, then \"Start now\" in Android's \"Share your screen\" prompt. Scroll the content slowly and steadily.\n" +
        "3. Tap the bubble again to stop. V-OCR reads the recording and a \"Text ready\" notification appears. Tap it to open the text.",
    "The floating bubble" to
        "Tap: start or stop a recording. The ring shows progress while recording or reading.\n" +
        "Drag: move it anywhere. After a few idle seconds it tucks against the screen edge; tap the edge to bring it back.\n" +
        "Long-press: opens three small controls: a red X to close the bubble (not while recording), a Fast/Slow switch, and an Auto-scroll switch. They disappear after 4 seconds.\n" +
        "The (+) chip next to the bubble is described under \"Queue and (+)\" below.",
    "Auto-scroll (hands-free recording)" to
        "Auto-scroll swipes the screen for you while it records, so you only tap start.\n" +
        "Setup, once: tap home screen > Settings > \"Auto-scroll\" (or the Auto-scroll switch on the bubble). V-OCR first shows a notice explaining what the permission is used for (swipes, plus tiny grey 48x96 screenshots kept only in memory to spot the bottom of the page). If you agree, Android Settings > Accessibility > Installed apps > V-OCR > turn on. If you just installed an update and it stops working, switch it off and on once.\n" +
        "Use: long-press the bubble and tap the Auto-scroll switch. Violet means armed, grey means off. Then start a recording on the page you want. V-OCR scrolls down for you.\n" +
        "It stops by itself when the page stops moving (3 swipes in a row with no change) and shows \"Reached the bottom - reading now\". A 5-minute cap also stops it as a safety net. You can always tap the bubble to stop sooner.\n" +
        "Speed: home screen > Settings > Auto-scroll speed, 0 to 10. 10 (default) is the best working pace. Lower numbers scroll slower and smoothly, down to a very slow crawl at 1, so you can read along. 0 stops the scrolling completely; the recording keeps running until you stop it. You can change the speed while recording.\n" +
        "Tip: start at the top of the content. Pages that load more as you scroll may keep moving; stop manually if needed.",
    "Turning on auto-scroll (step by step)" to
        "Phones block accessibility for apps installed from a downloaded file (not from a store) until you allow it once. The \"Allow restricted settings\" option only appears after Android has refused once, so do it in this order:\n" +
        "1. Settings > Accessibility > Installed apps > V-OCR. Try to switch it on. If V-OCR is greyed out (\"Controlled by restricted setting\"), tap it. Android shows \"App was denied access\". Tap Close.\n" +
        "2. Settings > Apps > V-OCR. Tap the three dots at the top right > \"Allow restricted settings\". Confirm with your PIN or fingerprint if asked.\n" +
        "3. Back to Settings > Accessibility > Installed apps > V-OCR. It now shows Off and is no longer greyed out. Switch it on, then tap Allow in Android's \"Allow V-OCR to have full control of your device?\" box.\n" +
        "That last box uses Android's standard wording for every accessibility app. V-OCR only swipes and takes tiny grey screenshots, as described at the top of this section.\n" +
        "If V-OCR is not greyed out in step 1, you can skip step 2. Steps can differ a little between phone makers; this was checked on a Samsung Galaxy S24 Ultra.",
    "Queue and (+)" to
        "V-OCR reads one recording at a time and lines the rest up. You can keep recording or adding while one is being read.\n" +
        "On the home screen, the ADD button (top right) opens the video picker. Pick one or several screen recordings; each becomes a job in the queue. The badge on it shows how many are still active.\n" +
        "On the floating bubble, tap (+) to choose: \"Add old recordings\" (picker) or \"New recording\" (starts a fresh recording, with auto-scroll if it is armed). The menu closes itself after 5 seconds.\n" +
        "In the queue list each job shows its progress and time left. Cancel stops a waiting or running job (a job in the middle of reading finishes its current step first). Open shows the text; Share file sends it.\n" +
        "Reading two long recordings at once is slower for both, so jobs wait for their turn.",
    "Reading time" to
        "Reading takes from under a minute to several minutes depending on how long the recording is and how dense the text is. Heat slows it down: let the phone cool if it has been working for a long time. Keep V-OCR's notification visible; reading continues in the background.",
    "Results, sharing and export" to
        "Every result is saved automatically as a .txt file in Downloads/V-OCR. The file name is the page header plus its first meaningful line.\n" +
        "\"Open V-OCR folder (all files)\" on the home screen opens that folder in your file manager.\n" +
        "From the text screen or a queue job you can Copy, or Share the text as an attached .txt file to any app (Mail, WhatsApp, Notes, Drive...).\n" +
        "The text screen also shows how many lines were read and how long it took.",
    "Settings" to
        "Fast mode (home screen, text screen, or bubble long-press): about 25% quicker, slightly less accurate. Off by default.\n" +
        "Settings button (home screen): opens Auto-scroll setup, Auto-scroll speed, the Sound switch and the screenshots switch.\n" +
        "Sound: on by default. Turn it off in Settings and none of the three sounds play (auto-scroll loop, scan loop, end-of-processing sound).\n" +
        "Show extracted screenshots: shows the kept frames (\"strips\") so you can check what was read. Off by default.\n" +
        "Auto-scroll: see above.\n" +
        "Everything else is automatic. There is no account and no cloud option.",
    "Permissions: what and why" to
        "Display over other apps: draws the floating bubble.\n" +
        "Screen sharing (asked by Android at every recording): lets V-OCR record the screen. Android 14 and later always asks; it cannot be skipped.\n" +
        "Notifications: shows recording and \"Text ready\" notices.\n" +
        "Accessibility (optional): only for auto-scroll. It lets V-OCR swipe and look at the screen to detect the bottom. It is not used for anything else.\n" +
        "Photos and videos picker: only for adding old recordings, through Android's own picker.",
    "Getting the best text" to
        "Scroll slowly and steadily; a fast fling can skip lines.\n" +
        "Keep the screen on and unlocked, and use normal brightness.\n" +
        "Hide pop-ups and floating notifications while you record.\n" +
        "Recording a single app: Android only lets V-OCR record what stays on screen. If you leave the app, the recording keeps what was shown but cannot follow it.\n" +
        "Long captures (capture tips): reading time follows the number of screens, not the length of the article. A page that shows more text per screen needs fewer screens, so it records and reads faster. For a long article, try your browser's desktop site mode, or a smaller text size, before you record.\n" +
        "Do not go too small: when letters look about 40% of the size of normal phone text or smaller, accuracy drops and words get misread. Do one short test capture first and check the result.\n" +
        "Light text on dark or dark on light both work. Handwriting and languages other than English and French are not supported.",
    "Troubleshooting" to
        "The bubble does not appear: allow \"Display over other apps\", then tap \"Floating recorder\" again.\n" +
        "Auto-scroll does nothing: check it is switched on in Accessibility and armed (violet) in the bubble menu; toggle it off and on after an update.\n" +
        "Words stuck together or a wrong letter: this can happen on small or blurry text. Re-record slower, or turn Fast mode off.\n" +
        "A job seems stuck: it may be waiting its turn or reading a long recording. The queue shows the stage and time left. Cancel it and re-add if nothing moves for several minutes.\n" +
        "Cannot find a file: use \"Open V-OCR folder (all files)\" or look in Downloads/V-OCR."
)

@Composable
fun Manual(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(GlassBackdrop).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232A45), contentColor = Color.White)) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text("User manual", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            for ((title, body) in sections) {
                Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(20.dp)).padding(18.dp)) {
                    Text(title, color = Lilac, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(body, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
