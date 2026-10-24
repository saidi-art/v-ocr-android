package app.vocr.sphere

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.vocr.R

/**
 * The charcoal bottom bar (brief: "the auto-capture bar is charcoal"). It rises while the sphere is dragged;
 * dropping the sphere on it starts auto capture - a measured part of the conversation, ended with Stop. While the
 * sphere hovers over it the bar lights (hot), so the target is unambiguous before the finger lifts.
 */
class DropBar(ctx: Context, private val palette: Palette) : FrameLayout(ctx) {
    var hot = false
        set(v) { if (field != v) { field = v; restyle() } }

    private val bg = GradientDrawable()
    private val label: TextView

    init {
        val d = resources.displayMetrics.density
        bg.cornerRadii = floatArrayOf(18f * d, 18f * d, 18f * d, 18f * d, 0f, 0f, 0f, 0f)
        background = bg
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_sph_auto); setColorFilter(Color.WHITE)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
            label = TextView(ctx).apply { text = "Auto capture"; setTextColor(Color.WHITE); textSize = 13f }
            addView(label)
        }
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        contentDescription = "Drop the sphere here to auto capture a measured part of the conversation. End it with Stop."
        restyle()
    }

    private fun restyle() {
        val base = palette.barBg
        bg.setColor(if (hot) lift(base, 0.18f) else base)
        bg.setStroke(((if (hot) 2f else 1f) * resources.displayMetrics.density).toInt(), if (hot) withA(palette.halo, 0.9f) else withA(Color.WHITE, 0.16f))
        label.alpha = if (hot) 1f else 0.85f
    }

    private fun lift(col: Int, f: Float): Int {
        val r = Color.red(col) + ((255 - Color.red(col)) * f).toInt()
        val g = Color.green(col) + ((255 - Color.green(col)) * f).toInt()
        val b = Color.blue(col) + ((255 - Color.blue(col)) * f).toInt()
        return Color.rgb(r, g, b)
    }
    private fun withA(col: Int, a: Float) = (col and 0x00FFFFFF) or ((255 * a).toInt() shl 24)
}

/** The red dot the sphere carries while REC records (brief: REC carries a fixed red dot). */
class RecDot(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - d
        p.style = Paint.Style.FILL; p.color = 0xFFE5484D.toInt(); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * d; p.color = Color.WHITE; c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL
    }
}
