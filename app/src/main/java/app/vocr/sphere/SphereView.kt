package app.vocr.sphere

import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * The shell's shapes (brief: "Shared geometry"). Perfect ovals at a 1.25:1 ratio and the round sphere; one fixed
 * light source, top-left - shapes move and scale, highlights never rotate. No irregular shapes in either theme.
 */

/** One glossy oval (or round, when w == h) drawn from a [highlight, body, shadow] triplet, with the fixed top-left light. */
class OvalView(ctx: Context) : View(ctx) {
    var colors: IntArray = intArrayOf(Color.WHITE, Color.LTGRAY, Color.GRAY); set(v) { field = v; invalidate() }
    var bodyStop = 0.55f; set(v) { field = v; invalidate() }
    /** Selected state: a thin white inner rim. A white inner rim always means active (brief). */
    var rim = false; set(v) { field = v; invalidate() }
    /** REC carries a fixed red dot in both themes (brief). */
    var redDot = false; set(v) { field = v; invalidate() }
    /** A coloured outer trim (Night desk: brass on emerald; Color.TRANSPARENT for none). */
    var trimColor = Color.TRANSPARENT; set(v) { field = v; invalidate() }
    var dimmed = false; set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); if (w <= 0 || h <= 0) return
        val d = resources.displayMetrics.density
        val a = if (dimmed) 0.5f else 1f
        val body = colors[1]
        // glow (theme supplies the halo colour through the highlight side; soft, local, never a dim layer behind tools)
        val gw = w * 0.35f
        p.shader = RadialGradient(w / 2, h / 2, w / 2 + gw, intArrayOf(withA(body, 0.35f * a), withA(body, 0.12f * a), 0), floatArrayOf(0f, w / 2 / (w / 2 + gw), 1f), Shader.TileMode.CLAMP)
        c.drawOval(-gw, -(gw * h / w) + h / 2 - h / 2, w + gw, h + (gw * h / w), p)
        // body: radial gradient centred upper-left (the fixed light), highlight -> body -> shadow
        val hx = w * 0.32f; val hy = h * 0.28f
        val gr = Math.hypot(w * 0.75.toDouble(), h * 0.75.toDouble()).toFloat()
        p.shader = RadialGradient(hx, hy, gr, intArrayOf(withA(colors[0], a), withA(body, a), withA(colors[2], a)), floatArrayOf(0f, bodyStop, 1f), Shader.TileMode.CLAMP)
        c.drawOval(0f, 0f, w, h, p)
        // outer trim (Night desk brass casing)
        if (trimColor != Color.TRANSPARENT) {
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f * d; p.color = withA(trimColor, a)
            c.drawOval(0.9f * d, 0.9f * d, w - 0.9f * d, h - 0.9f * d, p); p.style = Paint.Style.FILL
        }
        // thin light rim, always present, never rotated
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = withA(Color.WHITE, 0.45f * a)
        c.drawOval(0.6f * d, 0.6f * d, w - 0.6f * d, h - 0.6f * d, p); p.style = Paint.Style.FILL
        // selected: the white inner rim that always means active
        if (rim) { p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = withA(Color.WHITE, a); c.drawOval(2f * d, 2f * d, w - 2f * d, h - 2f * d, p); p.style = Paint.Style.FILL }
        // the fixed red dot for REC
        if (redDot) { p.color = withA(0xFFE5484D.toInt(), a); c.drawCircle(w - 7f * d, 6f * d, 3f * d, p) }
        p.shader = null
    }

    private fun withA(col: Int, a: Float) = (col and 0x00FFFFFF) or ((255 * a.coerceIn(0f, 1f)).toInt() shl 24)
}

/**
 * One touchable tool: the visual oval at its brief size inside a touch wrapper of at least 44 dp (48 for the sphere),
 * never overlapping a neighbour. Symbols only, no words inside shapes; [label] is used for accessibility and for the
 * one-time first-run hint beside the shape, never inside it.
 */
class ToolView(ctx: Context, triplet: IntArray, iconRes: Int, iconDp: Int, visualWdp: Int, visualHdp: Int,
               ink: Int, val label: String) : FrameLayout(ctx) {
    val oval = OvalView(ctx)
    private val icon = ImageView(ctx)
    init {
        val d = resources.displayMetrics.density
        oval.colors = triplet
        addView(oval, LayoutParams((visualWdp * d).toInt(), (visualHdp * d).toInt()).apply { gravity = android.view.Gravity.CENTER })
        icon.setImageResource(iconRes); icon.setColorFilter(ink)
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon, LayoutParams((iconDp * d).toInt(), (iconDp * d).toInt()).apply { gravity = android.view.Gravity.CENTER })
        contentDescription = label
        isFocusable = true
    }
    /** Selected state: scale up about 12%, stronger glow, thin white inner rim (handled by OvalView.rim + caller's scale anim). */
    fun setLit(lit: Boolean) { oval.rim = lit }
    fun setTrim(color: Int) { oval.trimColor = color }
    fun setRedDot(on: Boolean) { oval.redDot = on }
    fun setInk(color: Int) { icon.setColorFilter(color) }
}
