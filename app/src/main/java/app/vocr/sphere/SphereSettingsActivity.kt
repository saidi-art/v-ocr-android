package app.vocr.sphere

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import app.vocr.capture.CaptureService

/**
 * Sphere settings: only what Android cannot host (brief). Theme choice (Day, Night desk, or follow the phone),
 * the direct-text opt-in behind its disclosure, the account placeholder, and the privacy wording. Everything else
 * lives in Android's own settings screens.
 */
class SphereSettingsActivity : Activity() {
    private val dp get() = resources.displayMetrics.density

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
            // the sphere re-reads its palette when it next wakes
        }
        root.addView(group)
        root.addView(body("Default on first launch is an open brief decision; until it settles, the sphere follows the phone."))

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

        // Account + plan (foundation for the future service; all v1 features free)
        root.addView(h("Account"))
        val a = Account.current(this)
        root.addView(body("Account: ${a.label}. Every v1 feature is part of the free plan; future AI and agent layers will be paid options, shaped by real use. Sign-in arrives with the account service."))

        // Privacy wording (user-confirmed direction): captured content stays on the phone; account and any
        // analytics traffic are explained separately, not implied away by "everything stays on your phone".
        root.addView(h("Privacy"))
        root.addView(body("Everything you capture stays on your phone. Captures live in Download/Sphere and are easy to delete. Account sign-in and any analytics (not yet chosen) communicate online only for their own purpose and will be described separately."))

        setContentView(root)
    }
}
