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
 * The tool splash (brief: long press). Rescue chat, REC, Auto-scroll and Settings fan towards the screen centre,
 * staggered 55 ms, with the Pop spring; they chase the sphere with Trail while it is dragged. Speed is a sub-tool
 * shown only while Auto-scroll or REC runs (wired with the capture engine). No dim layer behind open tools.
 */
class Splash(private val ctx: Context, private val wm: WindowManager, private val ui: Handler) {
    enum class Tool { RESCUE, REC, AUTO, SETTINGS }
    interface Actions { fun onTool(t: Tool) }

    private var container: android.widget.LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var scrim: View? = null
    private var chase: SpringAnimation? = null
    private val reduced = app.vocr.VocrApp.reducedMotion()

    fun isOpen() = container != null

    /** Pops one tool in. Pop (stiffness 377, damping 0.70): fast out, small overshoot, quiet settle. */
    private fun popIn(v: View) {
        if (reduced) {   // Remove animations: a 144 ms fade only, no overshoot (brief)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Motions.F144; interpolator = LinearInterpolator()
                addUpdateListener { v.alpha = it.animatedValue as Float }
                start()
            }
            v.scaleX = 1f; v.scaleY = 1f
            return
        }
        v.alpha = 1f
        SpringAnimation(FloatValueHolder(0f))
            .setSpring(SpringForce(Springs.POP_STIFF).apply { dampingRatio = Springs.POP_DAMP })
            .addUpdateListener { _, value, _ -> val s = (value / 100f).coerceAtLeast(0f); v.scaleX = s; v.scaleY = s }
            .animateToFinalPosition(100f)
    }

    /** Opens the splash next to the sphere. [sphere] is the sphere window's rect in screen pixels. */
    fun open(p: Palette, sphere: Rect, actions: Actions) {
        close(animated = false)
        val d = ctx.resources.displayMetrics.density
        val night = p.night
        fun tool(triplet: IntArray, icon: Int, iconDp: Int, wDp: Int, hDp: Int, label: String, t: Tool): ToolView {
            val ink = if (night && (t == Tool.RESCUE || t == Tool.SETTINGS)) p.inkOnGlass else p.ink
            return ToolView(ctx, triplet, icon, iconDp, wDp, hDp, ink, label).apply {
                if (t == Tool.REC) setRedDot(true)   // REC carries a fixed red dot in both themes
                if (night && t == Tool.RESCUE) setTrim(p.trim)   // brass casing on the emerald pieces
                if (t == Tool.SETTINGS) { oval.colors = intArrayOf(p.settingsBg, p.settingsBg, p.settingsBg); if (night) setTrim(p.trim) }
                val pad = ((Geo.MIN_TOUCH_DP - hDp).coerceAtLeast(0) * d / 2).toInt()
                setPadding(pad, pad, pad, pad)
                setOnClickListener { it.performHapticFeedback(Haptic.PICK); close(); actions.onTool(t) }
            }
        }
        val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val tools = listOf(
            tool(p.rescue, app.vocr.R.drawable.ic_sph_rescue, Geo.LARGE_ICON_DP, Geo.LARGE_W_DP, Geo.LARGE_H_DP, "Rescue chat", Tool.RESCUE),
            tool(p.rec, app.vocr.R.drawable.ic_sph_rec, Geo.MED_ICON_DP, Geo.MED_W_DP, Geo.MED_H_DP, "REC: record what you scroll", Tool.REC),
            tool(p.auto, app.vocr.R.drawable.ic_sph_auto, Geo.MED_ICON_DP, Geo.MED_W_DP, Geo.MED_H_DP, "Auto-scroll", Tool.AUTO),
            tool(p.mini, app.vocr.R.drawable.ic_sph_settings, Geo.SMALL_ICON_DP, Geo.SMALL_W_DP, Geo.SMALL_H_DP, "Settings", Tool.SETTINGS))
        tools.forEachIndexed { i, v ->
            v.scaleX = 0f; v.scaleY = 0f; v.alpha = 0f
            col.addView(v)
            ui.postDelayed({ if (container != null) popIn(v) }, Motions.STAGGER * i)
        }
        // invisible scrim: a tap outside the tools dismisses the splash (no dim layer, per the brief)
        val sc = View(ctx).apply { setOnClickListener { close() } }
        runCatching {
            wm.addView(sc, WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT))
            scrim = sc
        }
        val q = params(sphere, col)
        runCatching { wm.addView(col, q); container = col; lp = q }
    }

    /** Window params for the tool column: fan towards the screen centre, mirroring with the sphere's edge. */
    private fun params(sphere: Rect, col: View): WindowManager.LayoutParams {
        val screenW = ctx.resources.displayMetrics.widthPixels
        val onRight = sphere.centerX() > screenW / 2
        val q = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        q.gravity = Gravity.TOP or Gravity.START
        col.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = col.measuredWidth; val h = col.measuredHeight
        q.x = if (onRight) sphere.left - w - 8 else sphere.right + 8
        q.y = (sphere.centerY() - h / 2).coerceIn(0, (ctx.resources.displayMetrics.heightPixels - h).coerceAtLeast(0))
        return q
    }

    /** The sphere moved while dragged: the open tools chase it with the Trail spring (144 / 0.60). */
    fun follow(sphere: Rect) {
        val col = container ?: return; val q = lp ?: return
        val target = params(sphere, col)
        if (reduced) { q.x = target.x; q.y = target.y; runCatching { wm.updateViewLayout(col, q) }; return }
        chase?.cancel()
        val fromX = q.x.toFloat(); val fromY = q.y.toFloat()
        chase = SpringAnimation(FloatValueHolder(0f))
            .setSpring(SpringForce(Springs.TRAIL_STIFF).apply { dampingRatio = Springs.TRAIL_DAMP })
            .addUpdateListener { _, value, _ ->
                q.x = (fromX + (target.x - fromX) * value / 100f).toInt(); q.y = (fromY + (target.y - fromY) * value / 100f).toInt()
                runCatching { wm.updateViewLayout(col, q) }
            }.apply { animateToFinalPosition(100f) }
    }

    fun close(animated: Boolean = true) {
        chase?.cancel(); chase = null
        scrim?.let { runCatching { wm.removeView(it) } }; scrim = null
        val col = container ?: return; container = null; lp = null
        if (!animated || reduced) { runCatching { wm.removeView(col) }; return }
        col.animate().scaleX(0.85f).scaleY(0.85f).alpha(0f).setDuration(Motions.F144).withEndAction { runCatching { wm.removeView(col) } }.start()
    }
}
