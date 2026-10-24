package app.vocr.sphere

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * The after-capture popup (brief). Four minis appear after every capture: Image, Text, Open and Share. The user
 * picks a format (Image or Text stays lit), then a destination: Open reviews the capture, Share hands it to a bot.
 * Either order works. The last-used format starts lit, so the usual path is one tap on Share. The popup shows only
 * after a capture, stays while touched, and vanishes ~3 s after the last touch; the capture stays in the shade if
 * it is missed. A one-time first-run hint names each mini (labels beside the shapes, never inside).
 */
class Popup(private val ctx: Context, private val wm: WindowManager, private val ui: Handler) {
    interface Actions {
        fun onOpen(m: CaptureStore.Meta, format: CaptureStore.Format)
        fun onShare(m: CaptureStore.Meta, format: CaptureStore.Format)
    }

    private var row: android.widget.LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var meta: CaptureStore.Meta? = null
    private var chase: SpringAnimation? = null
    private val reduced = app.vocr.VocrApp.reducedMotion()
    private val vanish = Runnable { close() }
    private var imageMini: ToolView? = null
    private var textMini: ToolView? = null

    fun isShowing() = row != null
    fun current(): CaptureStore.Meta? = meta

    private fun prefs() = ctx.getSharedPreferences("sphere", Context.MODE_PRIVATE)
    private fun lastFormat(): CaptureStore.Format =
        if (prefs().getString("last_format", "text") == "image") CaptureStore.Format.IMAGE else CaptureStore.Format.TEXT

    private fun popIn(v: View) {
        if (reduced) {
            ValueAnimator.ofFloat(0f, 1f).apply { duration = Motions.F144; interpolator = LinearInterpolator(); addUpdateListener { v.alpha = it.animatedValue as Float }; start() }
            v.scaleX = 1f; v.scaleY = 1f; return
        }
        v.alpha = 1f
        SpringAnimation(FloatValueHolder(0f))
            .setSpring(SpringForce(Springs.POP_STIFF).apply { dampingRatio = Springs.POP_DAMP })
            .addUpdateListener { _, value, _ -> val s = (value / 100f).coerceAtLeast(0f); v.scaleX = s; v.scaleY = s }
            .animateToFinalPosition(100f)
    }

    private fun lit(v: ToolView?, on: Boolean) {
        v ?: return
        v.setLit(on)
        val target = if (on) 1.12f else 1f   // selected: scale up about 12% (brief)
        if (reduced) { v.scaleX = target; v.scaleY = target; return }
        SpringAnimation(FloatValueHolder(v.scaleX * 100f))
            .setSpring(SpringForce(Springs.POP_STIFF).apply { dampingRatio = Springs.POP_DAMP })
            .addUpdateListener { _, value, _ -> val s = value / 100f; v.scaleX = s; v.scaleY = s }
            .animateToFinalPosition(target * 100f)
    }

    /** Shows the minis for a fresh capture next to the sphere. [sphere] is the sphere window's rect. */
    fun show(p: Palette, m: CaptureStore.Meta, sphere: Rect, actions: Actions) {
        close(animated = false)
        meta = m
        CaptureStore.logStep(ctx, m.baseName(), "popup-shown", "type=${m.type.wire}")
        val d = ctx.resources.displayMetrics.density
        var litFormat = lastFormat()
        if (m.textUri == null && litFormat == CaptureStore.Format.TEXT) litFormat = CaptureStore.Format.IMAGE   // no text was made: image is the honest lit choice
        if (m.imageUris.isEmpty() && litFormat == CaptureStore.Format.IMAGE && m.textUri != null) litFormat = CaptureStore.Format.TEXT

        fun mini(icon: Int, label: String, onTap: (ToolView) -> Unit): ToolView =
            ToolView(ctx, p.mini, icon, Geo.SMALL_ICON_DP, Geo.SMALL_W_DP, Geo.SMALL_H_DP, p.ink, label).apply {
                val pad = ((Geo.MIN_TOUCH_DP - Geo.SMALL_H_DP).coerceAtLeast(0) * d / 2).toInt()
                setPadding(pad, pad, pad, pad)
                setOnClickListener { touch(); onTap(this) }
            }

        val im = mini(app.vocr.R.drawable.ic_sph_image, "Image") {
            it.performHapticFeedback(Haptic.PICK)
            litFormat = CaptureStore.Format.IMAGE
            prefs().edit().putString("last_format", "image").apply()
            lit(imageMini, true); lit(textMini, false)
        }
        val tx = mini(app.vocr.R.drawable.ic_sph_text, "Text") {
            it.performHapticFeedback(Haptic.PICK)
            litFormat = CaptureStore.Format.TEXT
            prefs().edit().putString("last_format", "text").apply()
            lit(imageMini, false); lit(textMini, true)
        }
        imageMini = im; textMini = tx
        val hasText = m.textUri != null
        tx.isEnabled = hasText; tx.alpha = 0f   // alpha animated in; enabled state ready
        if (!hasText) { tx.oval.dimmed = true }
        im.isEnabled = m.imageUris.isNotEmpty(); if (!im.isEnabled) im.oval.dimmed = true

        val open = mini(app.vocr.R.drawable.ic_sph_open, "Open: review this capture") {
            it.performHapticFeedback(Haptic.READY)
            CaptureStore.logStep(ctx, m.baseName(), "open", "format=${litFormat.wire}")
            actions.onOpen(m, litFormat)
        }
        val share = mini(app.vocr.R.drawable.ic_sph_share, "Share: hand this capture to another app") {
            it.performHapticFeedback(Haptic.READY)
            CaptureStore.logStep(ctx, m.baseName(), "share", "format=${litFormat.wire}")
            actions.onShare(m, litFormat)
        }

        // labels for the one-time first-run hint, beside the shapes (never inside)
        val showHint = !prefs().getBoolean("hint_shown", false)
        val r = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        listOf(im, tx, open, share).forEachIndexed { i, v ->
            val cell = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(v)
                if (showHint) addView(android.widget.TextView(ctx).apply {
                    text = v.label.substringBefore(':'); textSize = 10f
                    setTextColor(if (p.night) 0xFFF3E7CB.toInt() else 0xFF191919.toInt())
                    setShadowLayer(2f, 0f, 1f, 0x80000000.toInt())
                })
            }
            cell.scaleX = 0f; cell.scaleY = 0f; cell.alpha = 0f
            r.addView(cell)
            ui.postDelayed({ if (row != null) popIn(cell) }, Motions.STAGGER * i)   // nearest first, staggered 55 ms
        }
        if (showHint) {
            prefs().edit().putBoolean("hint_shown", true).apply()
            ui.postDelayed({
                // the hint names each mini once, then leaves it to the shapes
                (0 until r.childCount).forEach { (r.getChildAt(it) as? android.widget.LinearLayout)?.let { c -> if (c.childCount > 1) c.removeViewAt(1) } }
            }, 6000)
        }
        lit(im, litFormat == CaptureStore.Format.IMAGE)
        lit(tx, litFormat == CaptureStore.Format.TEXT)

        val q = params(sphere, r)
        runCatching { wm.addView(r, q); row = r; lp = q }
        touch()
    }

    /** Any touch on the popup keeps it alive; the vanish timer restarts from the last touch. */
    fun touch() {
        ui.removeCallbacks(vanish)
        ui.postDelayed(vanish, Motions.POPUP_VANISH)
    }

    fun pauseVanish() { ui.removeCallbacks(vanish) }

    private fun params(sphere: Rect, r: View): WindowManager.LayoutParams {
        val screenW = ctx.resources.displayMetrics.widthPixels
        val onRight = sphere.centerX() > screenW / 2
        val q = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        q.gravity = Gravity.TOP or Gravity.START
        r.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = r.measuredWidth; val h = r.measuredHeight
        q.x = if (onRight) sphere.left - w - 8 else sphere.right + 8
        q.y = (sphere.centerY() - h / 2).coerceIn(0, (ctx.resources.displayMetrics.heightPixels - h).coerceAtLeast(0))
        return q
    }

    /** The sphere moved while dragged: the minis chase it (Trail), and the vanish timer pauses (caller pauses). */
    fun follow(sphere: Rect) {
        val r = row ?: return; val q = lp ?: return
        val target = params(sphere, r)
        if (reduced) { q.x = target.x; q.y = target.y; runCatching { wm.updateViewLayout(r, q) }; return }
        chase?.cancel()
        val fromX = q.x.toFloat(); val fromY = q.y.toFloat()
        chase = SpringAnimation(FloatValueHolder(0f))
            .setSpring(SpringForce(Springs.TRAIL_STIFF).apply { dampingRatio = Springs.TRAIL_DAMP })
            .addUpdateListener { _, value, _ ->
                q.x = (fromX + (target.x - fromX) * value / 100f).toInt(); q.y = (fromY + (target.y - fromY) * value / 100f).toInt()
                runCatching { wm.updateViewLayout(r, q) }
            }.apply { animateToFinalPosition(100f) }
    }

    fun close(animated: Boolean = true) {
        chase?.cancel(); chase = null
        ui.removeCallbacks(vanish)
        imageMini = null; textMini = null; meta = null
        val r = row ?: return; row = null; lp = null
        if (!animated || reduced) { runCatching { wm.removeView(r) }; return }
        r.animate().alpha(0f).setDuration(Motions.F144).withEndAction { runCatching { wm.removeView(r) } }.start()
    }
}
