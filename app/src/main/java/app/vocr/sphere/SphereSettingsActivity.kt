package app.vocr.sphere

import androidx.activity.ComponentActivity
import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
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

    /** Google sign-in (supported path): Credential Manager mints a Google ID token; Firebase Auth consumes it. */
    private fun googleSignIn(err: android.widget.TextView) {
        val webId = AccountService.webClientId(this) ?: return
        val req = GetCredentialRequest.Builder()
            .addCredentialOption(GetGoogleIdOption.Builder().setServerClientId(webId).setFilterByAuthorizedAccounts(false).build())
            .build()
        lifecycleScope.launch {
            try {
                val res = CredentialManager.create(this@SphereSettingsActivity).getCredential(this@SphereSettingsActivity, req)
                val token = GoogleIdTokenCredential.createFrom(res.credential.data).idToken
                AccountService.auth(this@SphereSettingsActivity)
                    ?.signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                    ?.addOnCompleteListener { t -> if (t.isSuccessful) recreate() else err.text = "Sign-in failed: ${t.exception?.localizedMessage ?: "unknown error"}" }
            } catch (e: GetCredentialCancellationException) {
                // the user cancelled: not an error, stay signed out
            } catch (e: GetCredentialException) {
                err.text = "Google sign-in is unavailable here: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Email sign-in / account creation on the configured service; errors are shown as returned, never hidden. */
    private fun emailAuth(create: Boolean, email: String, pass: String, err: android.widget.TextView) {
        val auth = AccountService.auth(this) ?: return
        if (email.isBlank() || pass.length < 6) { err.text = "Enter an email and a password of at least 6 characters."; return }
        val task = if (create) auth.createUserWithEmailAndPassword(email, pass) else auth.signInWithEmailAndPassword(email, pass)
        task.addOnCompleteListener { t ->
            if (t.isSuccessful) recreate()
            else err.text = (if (create) "Could not create the account: " else "Sign-in failed: ") + (t.exception?.localizedMessage ?: "unknown error")
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
                val err = android.widget.TextView(this).apply { setTextColor(Color.parseColor("#E8A0A0")); textSize = 13f; setPadding(0, (8 * d).toInt(), 0, 0) }
                val webId = AccountService.webClientId(this)
                root.addView(android.widget.Button(this).apply {
                    text = if (webId != null) "Sign in with Google" else "Sign in with Google (configuration incomplete)"
                    isEnabled = webId != null
                    setOnClickListener { googleSignIn(err) }
                })
                root.addView(body("Or with email:"))
                val em = android.widget.EditText(this).apply { hint = "Email"; setHintTextColor(Color.parseColor("#77F3E7CB")); setTextColor(Color.parseColor("#F3E7CB"))
                    inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; setSingleLine() }
                val pw = android.widget.EditText(this).apply { hint = "Password (6+ characters)"; setHintTextColor(Color.parseColor("#77F3E7CB")); setTextColor(Color.parseColor("#F3E7CB"))
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine() }
                root.addView(em); root.addView(pw)
                val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                btnRow.addView(android.widget.Button(this).apply { text = "Sign in"; setOnClickListener { emailAuth(false, em.text.toString().trim(), pw.text.toString(), err) } })
                btnRow.addView(android.widget.Button(this).apply { text = "Create account"; setOnClickListener { emailAuth(true, em.text.toString().trim(), pw.text.toString(), err) } })
                root.addView(btnRow)
                root.addView(body("Creating an account stores your email and sign-in details on the configured account service. Captures stay on this phone either way."))
                root.addView(err)
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
