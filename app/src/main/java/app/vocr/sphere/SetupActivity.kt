package app.vocr.sphere

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.vocr.capture.CaptureService
import app.vocr.capture.ScrollService

/**
 * Sphere setup. The sphere is the whole interface; this page exists only because Android itself must host these
 * switches (overlay permission, notifications, accessibility). Once the sphere is on screen, this page can be left
 * and never reopened.
 */
class SetupActivity : Activity() {
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.parseColor("#101013")
        build()
    }

    override fun onResume() { super.onResume(); if (findViewById<LinearLayout>(11) != null) build() }

    private fun row(parent: LinearLayout, title: String, ok: Boolean, action: () -> Unit) {
        val d = dp
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt()) }
        val t = TextView(this).apply {
            text = (if (ok) "✓  " else "○  ") + title
            setTextColor(if (ok) Color.parseColor("#9BD6A7") else Color.parseColor("#F3E7CB")); textSize = 15f
        }
        line.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!ok) line.addView(Button(this).apply { text = "Open"; setOnClickListener { action() } })
        parent.addView(line)
    }

    private fun build() {
        val d = dp
        val root = LinearLayout(this).apply {
            id = 11; orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
            setBackgroundColor(Color.parseColor("#101013"))
        }
        root.addView(TextView(this).apply { text = "Sphere"; setTextColor(Color.parseColor("#F3E7CB")); textSize = 30f })
        root.addView(TextView(this).apply {
            text = "Capture here, switch, drop there.\nThe sphere stays on screen across your apps. It needs these Android switches:"
            setTextColor(Color.parseColor("#CCF3E7CB")); textSize = 14f; setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        })
        row(root, "Show over other apps", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        row(root, "Notifications (the shade is the capture's fallback)", nm.areNotificationsEnabled()) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        row(root, "Accessibility (captures and Rescue)", ScrollService.instance != null) {
            app.vocr.DisclosureActivity.openAccessibility(this)
        }
        root.addView(TextView(this).apply {
            val a = Account.current(this@SetupActivity)
            text = "Account: ${a.label} - every v1 feature is on the free plan. Account sign-in is not connected in this development build; captured content stays on your phone."
            setTextColor(Color.parseColor("#99F3E7CB")); textSize = 13f; setPadding(0, (16 * d).toInt(), 0, (6 * d).toInt())
        })
        val on = SphereService.isOn(this)
        root.addView(Button(this).apply {
            text = if (on) "Hide the sphere" else "Show the sphere"
            setOnClickListener {
                val i = Intent(this@SetupActivity, SphereService::class.java).setAction(if (on) SphereService.ACTION_HIDE else SphereService.ACTION_SHOW)
                if (on) startService(i) else {
                    if (!Settings.canDrawOverlays(this@SetupActivity)) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return@setOnClickListener
                    }
                    androidx.core.content.ContextCompat.startForegroundService(this@SetupActivity, i)
                }
                this@apply.postDelayed({ build() }, 400)
            }
        })
        root.addView(TextView(this).apply {
            text = "Tap the sphere to capture. Hold it for tools. Drag it anywhere."
            setTextColor(Color.parseColor("#99F3E7CB")); textSize = 13f; setPadding(0, (14 * d).toInt(), 0, 0)
        })
        setContentView(root)
    }
}
