package app.vocr.capture

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The daily sphere. OKLCH colour: hue = day x golden angle, lightness about 0.72, chroma 0.17 capped to sRGB (the daily-sphere spec).
 * 137.5 exactly repeats every 144 days (only 144 colours), so the exact golden angle 137.50776 is used: 365 different colours.
 */
object SphereShade {
    private const val GOLDEN = 137.50776405
    private const val RAD = Math.PI / 180.0

    private fun lin(l: Double, c: Double, h: Double): DoubleArray {
        val a = c * Math.cos(h * RAD); val b = c * Math.sin(h * RAD)
        val l3 = Math.pow(l + 0.3963377774 * a + 0.2158037573 * b, 3.0)
        val m3 = Math.pow(l - 0.1055613458 * a - 0.0638541728 * b, 3.0)
        val s3 = Math.pow(l - 0.0894841775 * a - 1.291485548 * b, 3.0)
        return doubleArrayOf(4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3, -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3, -0.0041960863 * l3 - 0.7034186147 * m3 + 1.707614701 * s3)
    }
    private fun inGamut(v: DoubleArray) = v.all { it >= -1e-4 && it <= 1.0001 }
    private fun cap(l: Double, c: Double, h: Double): Double {
        if (inGamut(lin(l, c, h))) return c
        var lo = 0.0; var hi = c
        repeat(20) { val m = (lo + hi) / 2; if (inGamut(lin(l, m, h))) lo = m else hi = m }
        return lo
    }
    private fun gamma(v: Double): Double { val x = v.coerceIn(0.0, 1.0); return if (x <= 0.0031308) 12.92 * x else 1.055 * Math.pow(x, 1 / 2.4) - 0.055 }
    /** OKLCH to ARGB, chroma capped to what sRGB can show. */
    fun oklch(l: Double, c: Double, h: Double): Int {
        val v = lin(l, cap(l, c, h), h)
        fun ch(x: Double) = Math.round(gamma(x) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(v[0]) shl 16) or (ch(v[1]) shl 8) or ch(v[2])
    }
    private fun day(d: Int) = d.coerceIn(1, 365)
    private fun hue(d: Int) = (day(d) * GOLDEN) % 360.0
    /** The day's raw OKLCH hue: the fixed offsets of the Sphere shell's tools are measured from it. */
    fun hueOf(dayOfYear: Int): Double = hue(day(dayOfYear))
    /** A [highlight, body, shadow] triplet at any hue, for the shell's offset tools (Rescue +120, Auto-scroll +240, REC +300, minis +180). */
    fun tool(l: Double, c: Double, h: Double): IntArray {
        val hh = (h % 360.0 + 360.0) % 360.0
        return intArrayOf(oklch(Math.min(0.95, l + 0.15), c * 0.6, hh), oklch(l, c, hh), oklch(l - 0.20, c * 0.9, hh))
    }
    /** Large sphere: [highlight side, body, shadow side]. */
    fun big(dayOfYear: Int): IntArray {
        val d = day(dayOfYear); val h = hue(d)
        // a hair of lightness and chroma wander (two different irrationals) so no two days round to the same 8 bit colour
        val l = 0.72 + (if (h > 70 && h < 125) 0.06 else 0.0) + 0.015 * ((d * 0.7548776662) % 1.0) - 0.0075
        val c = 0.17 + 0.012 * ((d * 0.5698402910) % 1.0)
        val shHue = h + (((265 - h + 540) % 360) - 180) * 0.08   // the shadow side leans a little toward blue
        return intArrayOf(oklch(Math.min(0.95, l + 0.15), 0.10, h), oklch(l, c, h), oklch(l - 0.20, 0.15, shHue))
    }
    /** The four small spheres: the complementary hue, [highlight, body, shadow]. */
    fun small(dayOfYear: Int): IntArray {
        val h = (hue(day(dayOfYear)) + 180.0) % 360.0
        return intArrayOf(oklch(0.95, 0.08, h), oklch(0.80, 0.16, h), oklch(0.60, 0.14, h))
    }
    fun today(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
}

/** One glossy ball with an optional glow. The ball is drawn inside [pad] pixels of room for the glow. */
class Ball(ctx: Context, val colors: IntArray, private val bodyStop: Float, private val padPx: Float, private val big: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    var pulse = 0f; set(v) { field = v; invalidate() }
    var ring = false; set(v) { field = v; invalidate() }
    var dim = false; set(v) { field = v; invalidate() }
    private var anim: ValueAnimator? = null
    fun startPulse() {
        if (!big || anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f, 0f).apply { duration = 3400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); addUpdateListener { pulse = it.animatedValue as Float }; start() }
    }
    fun stopPulse() { anim?.cancel(); anim = null }
    override fun onDetachedFromWindow() { stopPulse(); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val s = minOf(width, height) - 2 * padPx; val r = s / 2f
        val a = if (dim) 0.4f else 1f
        val body = colors[1]
        // glow
        val glow = if (big) (20f + 10f * pulse) * d else 10f * d
        val ga = if (big) 0.6f else 0.8f
        p.shader = RadialGradient(cx, cy, r + glow, intArrayOf(withAlpha(body, ga * a * 0.55f), withAlpha(body, ga * a * 0.55f), 0), floatArrayOf(0f, r / (r + glow), 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r + glow, p)
        // body: radial gradient at 32% 28%, highlight - body - shadow side
        val left = cx - r; val top = cy - r
        val gr = (Math.hypot(0.68, 0.72) * s).toFloat()
        p.shader = RadialGradient(left + 0.32f * s, top + 0.28f * s, gr, intArrayOf(withAlpha(colors[0], a), withAlpha(body, a), withAlpha(colors[2], a)), floatArrayOf(0f, bodyStop, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        // thin light ring
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = Color.argb((255 * (if (big) 0.45f else 0.55f) * a).toInt(), 255, 255, 255); c.drawCircle(cx, cy, r, p)
        if (ring) { p.strokeWidth = 2.5f * d; p.color = Color.WHITE; c.drawCircle(cx, cy, r + 3f * d, p) }
        p.style = Paint.Style.FILL
        if (big) {   // wax-like glint: a soft white ellipse upper left, turned 32 degrees
            val ex = left + (0.17f + 0.18f) * s; val ey = top + (0.11f + 0.11f) * s; val rx = 0.18f * s; val ry = 0.11f * s
            c.save(); c.translate(ex, ey); c.rotate(-32f); c.scale(1f, ry / rx)
            p.shader = RadialGradient(0f, 0f, rx, intArrayOf(Color.argb((230 * a).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255)), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, rx, p); c.restore()
        }
        p.shader = null
    }
    private fun withAlpha(col: Int, a: Float) = (col and 0x00FFFFFF) or ((255 * a.coerceIn(0f, 1f)).toInt() shl 24)
}
