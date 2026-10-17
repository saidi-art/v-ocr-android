package app.vocr.capture

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Manual cover: an opaque rectangle drawn over a region you do not want in the recording (a playing video, a ticker).
 * Off by default. The rect is remembered per orientation. While recording the windows are NOT_TOUCHABLE and NOT_FOCUSABLE,
 * so gestures pass straight through. Optional status-bar strip covers the notification ticker and clock.
 */
class Cover(private val ctx: Context, private val wm: WindowManager, private val ui: android.os.Handler) {
    private var main: View? = null
    private var strip: View? = null
    private var editing = false
    private var doneChip: View? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun landscape() = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun key() = if (landscape()) "cover_l" else "cover_p"
    private fun p() = CaptureService.prefs(ctx)

    fun enabled() = AVAILABLE && p().getBoolean("cover", false)
    fun stripOn() = p().getBoolean("cover_strip", true)

    private fun statusBarH(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(28)
    }

    /** Saved rect in screen pixels, or the top third of the screen. */
    fun rect(): Rect {
        val m = ctx.resources.displayMetrics
        val s = p().getString(key(), null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        if (s != null && s.size == 4 && s[2] > s[0] && s[3] > s[1]) return Rect(s[0].coerceAtLeast(0), s[1].coerceAtLeast(0), s[2].coerceAtMost(m.widthPixels), s[3].coerceAtMost(m.heightPixels))
        return Rect(0, 0, m.widthPixels, m.heightPixels / 3)
    }

    private fun save(r: Rect) { p().edit().putString(key(), "${r.left},${r.top},${r.right},${r.bottom}").apply() }

    /** Everything the cover hides, for the auto-scroll start picker. */
    fun blocked(): List<Rect> {
        if (!enabled()) return emptyList()
        val m = ctx.resources.displayMetrics
        val l = arrayListOf(rect())
        if (stripOn()) l.add(Rect(0, 0, m.widthPixels, statusBarH()))
        return l
    }

    private fun lp(r: Rect, touchable: Boolean): WindowManager.LayoutParams {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(r.width(), r.height(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, f, PixelFormat.OPAQUE).apply {
            gravity = Gravity.TOP or Gravity.START; x = r.left; y = r.top
        }
    }

    /** Draw the cover(s) for recording. Not touchable. Caller waits about 300 ms before starting the recorder. */
    fun deploy() {
        remove()
        if (!enabled()) return
        val v = View(ctx).apply { setBackgroundColor(COLOR) }
        runCatching { wm.addView(v, lp(rect(), false)); main = v }
        if (stripOn()) {
            val m = ctx.resources.displayMetrics
            val s = View(ctx).apply { setBackgroundColor(COLOR) }
            runCatching { wm.addView(s, lp(Rect(0, 0, m.widthPixels, statusBarH()), false)); strip = s }
        }
    }

    fun remove() {
        editing = false
        doneChip?.let { c -> runCatching { wm.removeView(c) } }; doneChip = null
        main?.let { v -> runCatching { wm.removeView(v) } }; main = null
        strip?.let { v -> runCatching { wm.removeView(v) } }; strip = null
    }

    fun isEditing() = editing

    /** Edit mode: drag the box to move it, drag the corner handle to resize, tap Done to save. */
    fun edit() {
        remove(); editing = true
        val r = rect()
        val q = lp(r, true)
        val root = FrameLayout(ctx).apply { setBackgroundColor(COLOR) }
        val label = TextView(ctx).apply { text = "Cover. Drag to move"; setTextColor(Color.parseColor("#A9A6A0")); textSize = 13f; gravity = Gravity.CENTER }
        root.addView(label, FrameLayout.LayoutParams(-1, -1))
        val handle = View(ctx).apply { setBackgroundColor(Color.parseColor("#D2B08A")) }
        root.addView(handle, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END))
        val m = ctx.resources.displayMetrics
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var ow = 0; var oh = 0
        root.setOnTouchListener { _, e ->
            when (e.action) { MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = q.x; oy = q.y }
                MotionEvent.ACTION_MOVE -> {
                    q.x = (ox + (e.rawX - sx).toInt()).coerceIn(0, (m.widthPixels - q.width).coerceAtLeast(0))
                    q.y = (oy + (e.rawY - sy).toInt()).coerceIn(0, (m.heightPixels - q.height).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(root, q) } } }
            true
        }
        handle.setOnTouchListener { _, e ->
            when (e.action) { MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ow = q.width; oh = q.height }
                MotionEvent.ACTION_MOVE -> {
                    q.width = (ow + (e.rawX - sx).toInt()).coerceIn(dp(80), m.widthPixels - q.x)
                    q.height = (oh + (e.rawY - sy).toInt()).coerceIn(dp(60), m.heightPixels - q.y)
                    runCatching { wm.updateViewLayout(root, q) } } }
            true
        }
        runCatching { wm.addView(root, q); main = root }
        val done = glassChip(ctx, "Done", Color.parseColor("#D2B08A")).apply {
            setOnClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                save(Rect(q.x, q.y, q.x + q.width, q.y + q.height)); remove()
                android.widget.Toast.makeText(ctx, "Cover saved", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        val dq = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = m.heightPixels / 2
        }
        runCatching { wm.addView(done, dq); doneChip = done }
    }

    companion object { /** Parked: the Cover feature is hidden from the UI and never draws while false. */ const val AVAILABLE = false; val COLOR = Color.parseColor("#0B0B0D") }
}
