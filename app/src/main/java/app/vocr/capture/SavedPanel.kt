package app.vocr.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** What was just saved: the image part(s), an optional text file. */
class SavedItem(val images: List<Uri>, val textUri: Uri?, val text: String, val title: String, val sub: String, val mediaWord: String = "Image") {
    fun toExtras(i: Intent): Intent = i.putStringArrayListExtra("images", ArrayList(images.map { it.toString() })).putExtra("text_uri", textUri?.toString()).putExtra("text", if (text.length < 100_000) text else "")
        .putExtra("title", title).putExtra("sub", sub).putExtra("media_word", mediaWord)
    companion object {
        fun from(i: Intent) = SavedItem((i.getStringArrayListExtra("images") ?: arrayListOf()).map { Uri.parse(it) }, i.getStringExtra("text_uri")?.let { Uri.parse(it) }, i.getStringExtra("text") ?: "",
            i.getStringExtra("title") ?: "Saved", i.getStringExtra("sub") ?: "", i.getStringExtra("media_word") ?: "Image")
    }
}

object SavedActions {
    /** Open the chosen file: the text inside the app, the image in the viewer (the first part when there are several). */
    fun open(ctx: Context, it: SavedItem, text: Boolean) = runCatching {
        if (text) { it.textUri?.let { u -> ctx.startActivity(Intent(ctx, app.vocr.SavedActivity::class.java).setData(u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) } }
        else it.images.firstOrNull()?.let { u -> ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    /** Share the chosen file: all image parts together, or the text (pasted into chat apps, and attached as a file). */
    fun share(ctx: Context, it: SavedItem, text: Boolean) = runCatching {
        val imgs = it.images
        val send: Intent = if (text) {
            val u = it.textUri ?: return@runCatching
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, u).apply { if (it.text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, it.text); clipData = android.content.ClipData.newRawUri(it.title, u) }
        } else {
            if (imgs.isEmpty()) return@runCatching
            val s = if (imgs.size == 1) Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, imgs[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(imgs))
            val cd = android.content.ClipData.newRawUri(it.title, imgs[0]); imgs.drop(1).forEach { u -> cd.addItem(android.content.ClipData.Item(u)) }
            s.clipData = cd; s
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The sphere is the notification: one large sphere, no banner. A tap unfolds four much smaller spheres in the complementary colour:
 * 1 text and 2 image choose the file (image first, 1 dimmed when there is no text file), 3 open and 4 share act on the chosen one.
 */
class SphereStage(ctx: Context, private val item: SavedItem, private val onAct: () -> Unit, private val onTouch: () -> Unit, private val onToggle: (Boolean) -> Unit = {}) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    val closedPx = dp(BIG_PAD * 2 + BIG)                  // the window while closed: just the sphere and its glow
    val openW = dp(BIG_PAD + BIG / 2 + 71f + 18f + 56f).toInt()
    val openH = dp(2 * (60f + 24f)).toInt()
    private val orbCx = dp(BIG_PAD + BIG / 2); private val cy = openH / 2f
    private val orb = Ball(ctx, SphereShade.big(SphereShade.today()), 0.38f, dp(BIG_PAD), true)
    private val minis = ArrayList<Ball>(); private val labels = ArrayList<TextView>()
    private var textSel = false
    var open = false; private set
    private val hasText = item.textUri != null
    private val words = arrayOf("text", item.mediaWord.lowercase(), "open", "share")
    private val xs = floatArrayOf(44f, 71f, 71f, 44f); private val ys = floatArrayOf(-60f, -22.5f, 22.5f, 60f)

    init {
        clipChildren = false
        orb.layoutParams = LayoutParams(dp(BIG_PAD * 2 + BIG).toInt(), dp(BIG_PAD * 2 + BIG).toInt())
        val sc = SphereShade.small(SphereShade.today())
        for (i in 0 until 4) {
            val m = Ball(ctx, sc, 0.40f, dp(13f), false).apply { layoutParams = LayoutParams(dp(48f).toInt(), dp(48f).toInt()); alpha = 0f; scaleX = 0.2f; scaleY = 0.2f; visibility = GONE; dim = (i == 0 && !hasText) }
            val tv = TextView(ctx).apply { text = words[i]; setTextColor(Color.WHITE); textSize = 12f; setShadowLayer(3f * d, 0f, 1f * d, Color.argb(200, 0, 0, 0)); alpha = 0f; visibility = GONE; layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT) }
            val k = i
            m.setOnClickListener { onTouch(); choose(k) }
            minis.add(m); labels.add(tv)
        }
        labels.forEach { addView(it) }; minis.forEach { addView(it) }; addView(orb)
        orb.setOnClickListener { onTouch(); val n = !open; onToggle(n); setOpen(n, true) }
        refresh()
        orb.startPulse()
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        orb.layout((orbCx - orb.layoutParams.width / 2f).toInt(), (cy - orb.layoutParams.height / 2f).toInt(), (orbCx + orb.layoutParams.width / 2f).toInt(), (cy + orb.layoutParams.height / 2f).toInt())
        for (i in 0 until 4) {
            val m = minis[i]; val w = m.layoutParams.width
            val x = orbCx; val y = cy   // folded at the orb's centre; the translation unfolds it
            m.layout((x - w / 2f).toInt(), (y - w / 2f).toInt(), (x + w / 2f).toInt(), (y + w / 2f).toInt())
            val tv = labels[i]; tv.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            val lx = orbCx + dp(xs[i]) + dp(11f + 7f); val ly = cy + dp(ys[i]) - tv.measuredHeight / 2f
            tv.layout(lx.toInt(), ly.toInt(), (lx + tv.measuredWidth).toInt(), (ly + tv.measuredHeight).toInt())
        }
    }
    private fun choose(i: Int) {
        when (i) {
            0 -> if (hasText) { textSel = true; refresh() }
            1 -> { textSel = false; refresh() }
            2 -> { SavedActions.open(context, item, textSel); onAct() }
            3 -> { SavedActions.share(context, item, textSel); onAct() }
        }
    }
    private fun refresh() { minis[0].ring = textSel; minis[1].ring = !textSel; labels[0].typeface = if (textSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; labels[1].typeface = if (!textSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }

    fun setOpen(o: Boolean, animate: Boolean) {
        open = o
        orb.animate().scaleX(if (o) 0.9f else 1f).scaleY(if (o) 0.9f else 1f).setDuration(300).start()
        for (i in 0 until 4) {
            val m = minis[i]; val tv = labels[i]
            m.animate().cancel(); tv.animate().cancel()
            if (o) {
                m.visibility = VISIBLE; tv.visibility = VISIBLE
                val delay = if (animate) i * 70L else 0L
                m.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(dp(xs[i])).translationY(dp(ys[i])).setStartDelay(delay).setDuration(if (animate) 500 else 0).setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).start()
                tv.animate().alpha(0.9f).setStartDelay(if (animate) 350L else 0L).setDuration(if (animate) 300 else 0).start()
            } else {
                m.animate().alpha(0f).scaleX(0.2f).scaleY(0.2f).translationX(0f).translationY(0f).setStartDelay(0).setDuration(220).withEndAction { m.visibility = GONE }.start()
                tv.animate().alpha(0f).setStartDelay(0).setDuration(150).withEndAction { tv.visibility = GONE }.start()
            }
        }
    }
    companion object { const val BIG = 72f; const val BIG_PAD = 30f }
}

/** The sphere at the top of the screen, in the place of the heads-up. One tap unfolds the four small ones. Goes away by itself like a download notification. */
class SavedCard(private val ctx: Context, private val wm: WindowManager, private val ui: Handler = Handler(Looper.getMainLooper())) {
    private var root: FrameLayout? = null
    private val gone = Runnable { dismiss() }
    fun dismiss() { ui.removeCallbacks(gone); root?.let { v -> runCatching { wm.removeView(v) } }; root = null }

    fun show(item: SavedItem, statusBarPx: Int) {
        dismiss()
        val d = ctx.resources.displayMetrics.density
        val frame = FrameLayout(ctx)
        fun life(ms: Long) { ui.removeCallbacks(gone); ui.postDelayed(gone, ms) }
        lateinit var stage: SphereStage
        val closed = (SphereStage.BIG_PAD * 2 + SphereStage.BIG).let { (it * d).toInt() }
        val orbTop = (statusBarPx - 22 * d).coerceAtLeast(0f)   // the ball sits just under the status bar
        val orbCy = orbTop + closed / 2f
        val lp = WindowManager.LayoutParams(closed, closed, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = (6 * d).toInt(); y = orbTop.toInt() }
        // the stage has a fixed size; the window is only as big as what is showing, so the rest of the screen stays touchable
        fun place(open: Boolean) {
            val h = if (open) stage.openH else closed
            val w = if (open) stage.openW else closed
            val y = (orbCy - h / 2f).coerceAtLeast(0f)
            lp.width = w; lp.height = h; lp.y = y.toInt()
            stage.translationY = (orbCy - y) - stage.openH / 2f      // keeps the ball where it was
            runCatching { wm.updateViewLayout(frame, lp) }
        }
        stage = SphereStage(ctx, item, { dismiss() }, { life(10_000) }) { o -> if (o) { place(true); life(10_000) } else { ui.postDelayed({ if (!stage.open) place(false) }, 260); life(5_000) } }
        frame.addView(stage, FrameLayout.LayoutParams(stage.openW, stage.openH))
        frame.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) dismiss(); false }
        runCatching { stage.translationY = (orbCy - orbTop) - stage.openH / 2f - (orbCy - orbTop - closed / 2f); wm.addView(frame, lp); root = frame; place(false); frame.translationY = -80 * d; frame.animate().translationY(0f).setDuration(180).start(); life(5_000) }
    }
}

/** Opened by a tap on the notification in the shade: the sphere with its four small ones, unfolded, on a dim background. */
class SavedPanelActivity : Activity() {
    override fun onCreate(b: android.os.Bundle?) {
        super.onCreate(b)
        val item = SavedItem.from(intent)
        val stage = SphereStage(this, item, { finish() }, { })
        val root = FrameLayout(this).apply { setBackgroundColor(Color.argb(110, 0, 0, 0)); setOnClickListener { finish() } }
        root.addView(stage, FrameLayout.LayoutParams(stage.openW, stage.openH, Gravity.CENTER))
        setContentView(root)
        stage.post { stage.setOpen(true, true) }
    }
}
