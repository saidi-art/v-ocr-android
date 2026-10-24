package app.vocr.sphere

import androidx.activity.ComponentActivity
import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.GoogleAuthProvider
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import app.vocr.capture.CaptureService

/**
 * Sphere settings: only what Android cannot host (brief). Theme choice (Day, Night desk, or follow the phone),
 * the direct-text opt-in behind its disclosure, the account placeholder, and the privacy wording. Everything else
 * lives in Android's own settings screens.
 */
class SphereSettingsActivity : ComponentActivity() {   // ComponentActivity: the sign-in result launcher needs it
    private val dp get() = resources.displayMetrics.density

    /** Google sign-in: mints a Google ID token and hands it to Firebase Auth. Cancel leaves the status signed out. */
    private val signIn = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val auth = AccountService.auth(this) ?: return@registerForActivityResult
        runCatching {
            val acct = GoogleSignIn.getSignedInAccountFromIntent(r.data).getResult(com.google.android.gms.common.api.ApiException::class.java)
            val token = acct.idToken ?: return@runCatching
            auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).addOnCompleteListener { recreate() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.parseColor("#101013")
        val d = dp
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
            setBackgroundColor(Color.parseColor("#101013"))
        }
        fun h(t: String) = TextView(this).apply { text = t; setTextColor(Color.parseColor("#F3E7CB")); textSize = 17f; setPadding(0, (14 * d).toInt(), 0, (6 * d).toInt()) }
        fun body(t: String) = TextView(this).apply { text = t; setTextColor(Color.parseColor("#99F3E7CB")); textSize = 13f }

        root.addView(TextView(this).apply { text = "Sphere settings"; setTextColor(Color.parseColor("#F3E7CB")); textSize = 26f })

        // Theme (brief: the tool splash's Settings includes the theme choice)
        root.addView(h("Theme"))
        val p = SphereService.prefs(this)
        val group = RadioGroup(this)
        val day = RadioButton(this).apply { text = "Day - vivid daily colour"; setTextColor(Color.parseColor("#F3E7CB")) }
        val night = RadioButton(this).apply { text = "Night desk - emerald and brass"; setTextColor(Color.parseColor("#F3E7CB")) }
        val sys = RadioButton(this).apply { text = "Follow the phone's dark mode"; setTextColor(Color.parseColor("#F3E7CB")) }
        group.addView(day); group.addView(night); group.addView(sys)
        when (p.getString("theme", "system")) { "day" -> day.isChecked = true; "night" -> night.isChecked = true; else -> sys.isChecked = true }
        group.setOnCheckedChangeListener { _, id ->
            p.edit().putString("theme", if (id == day.id) "day" else if (id == night.id) "night" else "system").apply()
            if (SphereService.isOn(this)) startService(Intent(this@SphereSettingsActivity, SphereService::class.java).setAction(SphereService.ACTION_THEME))   // the sphere re-themes in place
        }
        root.addView(group)
        root.addView(body("The sphere re-themes the moment you pick. Until you choose, it follows the phone."))

        // Direct text (experimental, opt-in, off by default, behind the disclosure)
        root.addView(h("Direct text"))
        val ep = CaptureService.prefs(this)
        val direct = android.widget.Switch(this).apply {
            text = "Read text straight from the screen (accessibility), instead of OCR"
            setTextColor(Color.parseColor("#F3E7CB"))
            isChecked = ep.getBoolean("direct_text", false)
            setOnCheckedChangeListener { _, on ->
                if (on) { ep.edit().putBoolean("direct_text", true).apply(); app.vocr.DisclosureActivity.openAccessibility(this@SphereSettingsActivity) }
                else ep.edit().putBoolean("direct_text", false).apply()
            }
        }
        root.addView(direct)
        root.addView(body("Both read on the device, and the capture's header says which one was used."))

        // Account (a decided v1 requirement): honest status, and the sign-in control only when this build is configured
        root.addView(h("Account"))
        when (val st = AccountService.status(this)) {
            is AccountService.Status.NotConnected ->
                root.addView(body("Every v1 feature is part of the free plan. Account sign-in is not connected in this development build."))
            is AccountService.Status.SignedOut -> {
                root.addView(body("Not signed in. Every v1 feature is part of the free plan."))
                val webId = AccountService.webClientId(this)
                root.addView(android.widget.Button(this).apply {
                    text = "Sign in with Google"
                    isEnabled = webId != null
                    if (webId == null) text = "Sign in with Google (configuration incomplete)"
                    setOnClickListener { webId?.let { id -> signIn.launch(GoogleSignIn.getClient(this@SphereSettingsActivity,
                        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(id).requestEmail().build()).signInIntent) } }
                })
            }
            is AccountService.Status.SignedIn -> {
                root.addView(body("Signed in as ${st.label}. Free plan - every v1 feature is included."))
                root.addView(android.widget.Button(this).apply { text = "Sign out"; setOnClickListener { AccountService.signOut(this@SphereSettingsActivity); recreate() } })
            }
        }

        // Privacy wording (user-confirmed direction): captured content stays on the phone; account and any
        // analytics traffic are explained separately, not implied away by "everything stays on your phone".
        root.addView(h("Privacy"))
        root.addView(body("Everything you capture stays on your phone. Captures live in Download/Sphere and are easy to delete. Captured content stays on this phone; what an account sends, once connected, will be described here before you use it."))

        setContentView(root)
    }
}
