package app.vocr.capture

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Glass look for the floating widget: translucent layered gradients, a light rim, a specular crescent, a soft shadow. No real blur (Android cannot blur behind a small overlay reliably). */
internal fun mix(c: Int, with: Int, t: Float): Int {
    fun m(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
    return Color.rgb(m(Color.red(c), Color.red(with)), m(Color.green(c), Color.green(with)), m(Color.blue(c), Color.blue(with)))
}

/** Pill-shaped glass chip used by the long-press and (+) menus. */
internal fun glassChip(ctx: Context, text: String, bg: Int): TextView = TextView(ctx).apply {
    val d = ctx.resources.displayMetrics.density
    this.text = text; setTextColor(if (androidx.core.graphics.ColorUtils.calculateLuminance(bg) > 0.4) Color.parseColor("#17120C") else Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
    setShadowLayer(if (androidx.core.graphics.ColorUtils.calculateLuminance(bg) > 0.4) 0f else 2f * d, 0f, 1f * d, Color.parseColor("#99000000"))
    setPadding((13 * d).toInt(), (7 * d).toInt(), (13 * d).toInt(), (7 * d).toInt())
    val top = Color.argb(0xE6, Color.red(mix(bg, Color.WHITE, 0.30f)), Color.green(mix(bg, Color.WHITE, 0.30f)), Color.blue(mix(bg, Color.WHITE, 0.30f)))
    val bot = Color.argb(0xD0, Color.red(mix(bg, Color.BLACK, 0.25f)), Color.green(mix(bg, Color.BLACK, 0.25f)), Color.blue(mix(bg, Color.BLACK, 0.25f)))
    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bot)).apply {
        cornerRadius = 22f * d; setStroke((1.2f * d).toInt().coerceAtLeast(1), Color.parseColor("#66FFFFFF"))
    }
}

/** Round floating button with a progress ring on its circumference. */
internal class Bubble(ctx: Context) : View(ctx) {
    var label = "REC"; var fill = Color.parseColor("#3A3B42"); var ring = -1f; var labelSp = 11f
    var pressedState = false
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG); private val pr = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val pg = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // setShadowLayer on shapes needs software rendering; the view is tiny
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.contentDescription = when {
            label == "REC" -> "Record. Starts a screen recording"
            label == "STOP" -> "Stop"
            label == "SCAN" -> "Reading the recording"
            label.endsWith("%") -> "Reading the recording, $label done"
            else -> label
        }
    }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density; val w = width.toFloat(); val h = height.toFloat()
        val sw = 4f * d; pr.strokeWidth = sw
        val ringRect = RectF(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
        val s = if (pressedState) 0.93f else 1f
        c.save(); c.scale(s, s, w / 2, h / 2)
        val body = RectF(sw + 1 * d, sw, w - sw - 1 * d, h - sw - 2 * d)
        val cx = body.centerX(); val cy = body.centerY(); val rad = body.width() / 2
        // 1. soft shadow + translucent base, lighter at the top, darker at the bottom
        pf.setShadowLayer(if (pressedState) 3f * d else 6f * d, 0f, if (pressedState) 1f * d else 3f * d, Color.parseColor("#80000000"))
        val up = mix(fill, Color.WHITE, if (pressedState) 0.10f else 0.38f); val dn = mix(fill, Color.BLACK, if (pressedState) 0.40f else 0.30f)
        pf.shader = LinearGradient(0f, body.top, 0f, body.bottom, Color.argb(0xE6, Color.red(up), Color.green(up), Color.blue(up)), Color.argb(0xD2, Color.red(dn), Color.green(dn), Color.blue(dn)), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, pf)
        pf.clearShadowLayer(); pf.shader = null
        // 2. rim glow: transparent in the middle, light at the edge
        pg.shader = RadialGradient(cx, cy, rad, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(if (pressedState) 0x20 else 0x55, 255, 255, 255)), floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, pg)
        // 3. specular crescent on the upper half (dimmer when pressed)
        val spec = RectF(cx - rad * 0.70f, body.top + rad * 0.10f, cx + rad * 0.70f, body.top + rad * 1.05f)
        pg.shader = LinearGradient(0f, spec.top, 0f, spec.bottom, Color.argb(if (pressedState) 0x30 else 0x8C, 255, 255, 255), Color.argb(0x00, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawOval(spec, pg)
        // 4. light edge, bright at the top and fading to the bottom
        pg.shader = null
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.3f * d
            shader = LinearGradient(0f, body.top, 0f, body.bottom, Color.argb(0xCC, 255, 255, 255), Color.argb(0x33, 255, 255, 255), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, rad - 0.6f * d, edge)
        // 5. progress ring with a faint glow
        if (ring >= 0f) {
            pr.clearShadowLayer()
            pr.color = Color.parseColor("#33FFFFFF"); c.drawOval(ringRect, pr)
            pr.setShadowLayer(4f * d, 0f, 0f, if (app.vocr.Pal.paper) Color.parseColor("#AAF9F6F0") else Color.parseColor("#AAE3C7A2"))
            pr.color = if (app.vocr.Pal.paper) Color.parseColor("#F9F6F0") else Color.parseColor("#E3C7A2"); c.drawArc(ringRect, -90f, 360f * ring.coerceIn(0f, 1f), false, pr)
            pr.clearShadowLayer()
        }
        // 6. label
        pt.textSize = labelSp * d
        pt.setShadowLayer(2f * d, 0f, 1f * d, Color.parseColor("#99000000"))
        c.drawText(label, w / 2, h / 2 - (pt.descent() + pt.ascent()) / 2 - 1 * d, pt)
        c.restore()
    }
}

/** Circled plus (the ADD button), shown above the widget while recordings are being read. Badge = jobs in the queue. */
internal class AddChip(ctx: Context) : View(ctx) {
    var count = 0
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val pb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9A7650") }
    private val pg = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.contentDescription = if (count > 1) "Add recordings. $count in the queue" else "Add recordings"
    }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density; val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2
        val rad = w / 2 - 4 * d
        pf.setShadowLayer(5f * d, 0f, 2f * d, Color.parseColor("#80000000"))
        pf.shader = LinearGradient(0f, cy - rad, 0f, cy + rad, Color.argb(0xEE, 0x7B, 0x88, 0xC8), Color.argb(0xDC, 0x2C, 0x34, 0x66), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, pf); pf.clearShadowLayer(); pf.shader = null
        pg.shader = LinearGradient(0f, cy - rad, 0f, cy, Color.argb(0x70, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - rad * 0.7f, cy - rad * 0.92f, cx + rad * 0.7f, cy + rad * 0.05f), pg)
        ps.strokeWidth = 1.2f * d; ps.alpha = 0xAA; c.drawCircle(cx, cy, rad - 0.6f * d, ps); ps.alpha = 255
        ps.strokeWidth = 2.5f * d; val a = 7f * d
        ps.setShadowLayer(2f * d, 0f, 1f * d, Color.parseColor("#99000000"))
        c.drawLine(cx - a, cy, cx + a, cy, ps); c.drawLine(cx, cy - a, cx, cy + a, ps)
        ps.clearShadowLayer()
        if (count > 1) { c.drawCircle(w - 8 * d, 8 * d, 8 * d, pb); pt.textSize = 10f * d; c.drawText(count.toString(), w - 8 * d, 8 * d - (pt.descent() + pt.ascent()) / 2, pt) }
    }
}

// Overlay palette (Graphite or Paper). Read when a menu is built, so a theme change shows the next time it opens.
internal fun accentHex() = if (app.vocr.Pal.paper) "#0C0C0C" else "#D2B08A"
internal fun accentC() = Color.parseColor(accentHex())
internal fun chipHex() = if (app.vocr.Pal.paper) "#E9E3D6" else "#25262B"
internal fun chipC() = Color.parseColor(chipHex())
internal fun onAccentC() = Color.parseColor(if (app.vocr.Pal.paper) "#F9F6F0" else "#17120C")
internal fun mutedC() = Color.parseColor(if (app.vocr.Pal.paper) "#3A3A38" else "#A9A6A0")
