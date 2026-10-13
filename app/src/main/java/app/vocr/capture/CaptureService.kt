package app.vocr.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.vocr.core.Pipeline
import app.vocr.core.Reader
import app.vocr.core.Title
import java.io.File

/**
 * Floating record button. Tap: start recording the screen. Tap again: stop, read the text in the background,
 * and post a notification with the finished .txt (named after the text).
 */
class CaptureService : Service() {
    companion object {
        const val ACTION_WIDGET = "app.vocr.WIDGET"
        const val ACTION_BEGIN = "app.vocr.BEGIN"
        const val ACTION_DENIED = "app.vocr.DENIED"
        const val ACTION_STOP = "app.vocr.STOP"
        const val ACTION_CLOSE = "app.vocr.CLOSE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CH_STATUS = "vocr_status2"
        private const val CH_DONE = "vocr_done2"
        private const val ID_FG = 41
        fun prefs(ctx: Context) = ctx.getSharedPreferences("vocr", Context.MODE_PRIVATE)
    }

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var widget: Bubble? = null
    private var menu: List<View> = emptyList()
    private var lp: WindowManager.LayoutParams? = null
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var recFile: File? = null
    private var recording = false
    private val busy: Boolean get() = app.vocr.core.JobQueue.activeCount() > 0
    private var addChip: AddChip? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BEGIN -> beginRecording(intent)
            ACTION_DENIED -> { setWidgetIdle(); toast("Screen recording was not allowed") }
            ACTION_STOP -> stopRecording()
            ACTION_CLOSE -> closeWidget()
            else -> { fgIdle("Tap the floating button to record"); addWidget() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { stopAutoScroll(); removeWidget(); releaseRecording(); super.onDestroy() }
    override fun onConfigurationChanged(c: android.content.res.Configuration) { super.onConfigurationChanged(c); hideMenu(); ui.post { if (widget != null) { lp?.let { it.y = it.y.coerceIn(0, (resources.displayMetrics.heightPixels - dp(60)).coerceAtLeast(0)) }; tuck(true) } } }

    // ---- notifications --------------------------------------------------------------------------------------------
    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun channels() {
        runCatching { nm().deleteNotificationChannel("vocr_status"); nm().deleteNotificationChannel("vocr_done") }
        nm().createNotificationChannel(NotificationChannel(CH_STATUS, "Recorder status", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm().createNotificationChannel(NotificationChannel(CH_DONE, "Text ready", NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) })
    }

    private fun act(action: String, label: String): Notification.Action {
        val pi = PendingIntent.getService(this, action.hashCode(), Intent(this, CaptureService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, label, pi).build()
    }

    private fun statusNote(text: String, withStop: Boolean = false, progress: Pair<Int, Int>? = null): Notification {
        channels()
        val b = Notification.Builder(this, CH_STATUS).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("V-OCR").setContentText(text).setOngoing(true)
        if (progress != null) b.setProgress(progress.second, progress.first, false)
        if (withStop) b.addAction(act(ACTION_STOP, "Stop and read"))
        else b.addAction(act(ACTION_CLOSE, "Close recorder"))
        return b.build()
    }

    private fun fgIdle(text: String) {
        val n = statusNote(text)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID_FG, n)
    }

    private fun fgRecording() {
        val n = statusNote("Recording the screen. Scroll, then tap Stop.", withStop = true)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(ID_FG, n)
    }

    private fun toast(t: String) = ui.post { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show() }

    // ---- floating widget ------------------------------------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var tucked = true
    private var sideRight = true
    private val tuckRun = Runnable { tuck(true) }

    private fun screenW() = resources.displayMetrics.widthPixels

    private fun targetX(tuckedNow: Boolean): Int {
        val w = lp?.width ?: dp(52)
        return if (sideRight) { if (tuckedNow) screenW() - dp(32) else screenW() - w - dp(6) } else { if (tuckedNow) -(w - dp(32)) else dp(6) }
    }

    private fun slideTo(x: Int) {
        val p = lp ?: return; val w = widget ?: return
        android.animation.ValueAnimator.ofInt(p.x, x).apply {
            duration = 180
            addUpdateListener { p.x = it.animatedValue as Int; runCatching { wm?.updateViewLayout(w, p) } }
            start()
        }
    }

    /** Collapse to a thin edge sliver (or pull back out). */
    private fun tuck(on: Boolean) {
        ui.removeCallbacks(tuckRun)
        if (on && busy) return   // stay visible while reading so the progress ring can be seen
        tucked = on
        widget?.alpha = if (on) 0.6f else if (recording) 0.55f else 1f
        slideTo(targetX(on))
        if (!on) ui.postDelayed(tuckRun, 3000)
    }

    private fun addWidget() {
        if (widget != null) return
        app.vocr.core.JobQueue.listener = { ui.post { refreshQueueUi() } }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = Bubble(this)
        val p = WindowManager.LayoutParams(dp(52), dp(52), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = screenW() - dp(32); y = dp(220)
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var longDone = false; var wasTucked = false
        val longRun = Runnable {
            longDone = true
            showMenu()
        }
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false; longDone = false
                    ui.removeCallbacks(tuckRun)
                    wasTucked = tucked
                    if (tucked) tuck(false)            // first touch on the sliver slides the bubble out
                    else ui.postDelayed(longRun, 600)  // long press opens the close / Fast-Slow controls
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - sx) > dp(6) || Math.abs(e.rawY - sy) > dp(6)) { moved = true; ui.removeCallbacks(longRun) }
                    if (moved) { p.x = ox + (e.rawX - sx).toInt(); p.y = oy + (e.rawY - sy).toInt(); wm?.updateViewLayout(tv, p) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longRun)
                    if (moved) { sideRight = p.x + p.width / 2 > screenW() / 2; tuck(false) }   // snap to the nearest edge, tuck after a moment
                    else if (!longDone && !wasTucked && e.action == MotionEvent.ACTION_UP) { onWidgetTap(); ui.postDelayed(tuckRun, 3000) }
                    else if (wasTucked) { /* just pulled out */ }
                }
            }
            true
        }
        widget = tv; lp = p
        wm?.addView(tv, p)
        ui.postDelayed(tuckRun, 3000)
    }

    private fun hideMenu() { ui.removeCallbacks(hideMenuRun); menu.forEach { v -> runCatching { wm?.removeView(v) } }; menu = emptyList() }
    private val hideMenuRun = Runnable { hideMenu() }

    private fun chip(text: String, bg: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(bg) }
        setOnClickListener { onClick() }
    }

    private fun overlay(v: View, x: Int, y: Int) {
        val q = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
        wm?.addView(v, q)
    }

    /** Long press: a close X at the top-left corner of the bubble and a Fast/Slow switch below it. */
    private fun showMenu() {
        val p = lp ?: return
        hideMenu(); tuck(false)
        val close = chip("\u00D7", Color.parseColor("#E5484D")) {
            hideMenu(); if (recording) toast("Stop recording first") else closeWidget()
        }
        fun modeText() = if (prefs(this).getBoolean("fast", false)) "Fast" else "Slow"
        val mode = chip(modeText(), Color.parseColor("#232A45")) {}
        mode.setOnClickListener {
            val f = !prefs(this).getBoolean("fast", false); prefs(this).edit().putBoolean("fast", f).apply()
            mode.text = modeText(); ui.removeCallbacks(hideMenuRun); ui.postDelayed(hideMenuRun, 4000)
        }
        fun autoText() = if (prefs(this).getBoolean("autoscroll", false)) "Auto-scroll ON" else "Auto-scroll off"
        fun autoOn() = prefs(this).getBoolean("autoscroll", false) && ScrollService.instance != null
        val auto = chip(autoText(), Color.parseColor("#232A45")) {}
        fun styleAuto() {   // violet = armed, slate + dim text = off, so the state reads at a glance
            (auto.background as GradientDrawable).setColor(Color.parseColor(if (autoOn()) "#7C5CFF" else "#232A45"))
            auto.setTextColor(if (autoOn()) Color.WHITE else Color.parseColor("#8A93AD"))
        }
        styleAuto()
        auto.setOnClickListener {
            if (ScrollService.instance == null) {
                toast("Auto-scroll needs Accessibility. Read the notice first.")
                hideMenu()
                app.vocr.DisclosureActivity.openAccessibility(this)
            } else {
                val a = !prefs(this).getBoolean("autoscroll", false); prefs(this).edit().putBoolean("autoscroll", a).apply()
                auto.text = autoText(); styleAuto(); ui.removeCallbacks(hideMenuRun); ui.postDelayed(hideMenuRun, 4000)
            }
        }
        overlay(close, p.x - dp(14), p.y - dp(30))
        overlay(mode, p.x - dp(6), p.y + p.height + dp(6))
        overlay(auto, p.x - dp(6), p.y + p.height + dp(46))
        menu = listOf(close, mode, auto)
        ui.postDelayed(hideMenuRun, 4000)
    }

    private fun setRing(f: Float, label: String?) {
        widget?.let { it.ring = f; if (label != null) it.label = label; it.invalidate() }
    }

    private fun closeWidget() { removeWidget(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    private fun removeWidget() { app.vocr.core.JobQueue.listener = null; hideAddChip(); hideMenu(); ui.removeCallbacks(tuckRun); widget?.let { runCatching { wm?.removeView(it) } }; widget = null }

    private fun setWidgetIdle() {
        widget?.apply { label = "REC"; alpha = 1f; labelSp = 11f; fill = Color.parseColor("#7C5CFF"); ring = -1f; invalidate() }
        lp?.let { it.width = dp(52); it.height = dp(52); widget?.let { w -> runCatching { wm?.updateViewLayout(w, it) } } }
        tuck(false)
    }

    private fun setWidgetRecording() {
        widget?.apply { label = "STOP"; alpha = 0.55f; labelSp = 9f; fill = Color.parseColor("#E5484D"); ring = -1f; invalidate() }
        lp?.let { it.width = dp(40); it.height = dp(40); widget?.let { w -> runCatching { wm?.updateViewLayout(w, it) } } }
        tuck(false)
    }

    private fun onWidgetTap() {
        if (recording) stopRecording()
        else startActivity(Intent(this, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- recording ------------------------------------------------------------------------------------------------
    private fun beginRecording(intent: Intent) {
        fgRecording()   // must be a media-projection foreground service before the projection is created
        try {
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            @Suppress("DEPRECATION") val data: Intent = intent.getParcelableExtra(EXTRA_DATA)!!
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mgr.getMediaProjection(code, data)!!
            mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() { ui.post { if (recording) stopRecording() } } }, ui)
            val m = resources.displayMetrics
            var w = m.widthPixels; var h = m.heightPixels
            val maxW = 1080; if (w > maxW) { h = h * maxW / w; w = maxW }
            w = w / 2 * 2; h = h / 2 * 2
            val f = File(cacheDir, "rec-" + System.currentTimeMillis() + ".mp4")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(w, h); r.setVideoFrameRate(60); r.setVideoEncodingBitRate(10_000_000)
            r.setOutputFile(f.path)
            r.prepare()
            display = mp.createVirtualDisplay("vocr", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
            r.start()
            projection = mp; recorder = r; recFile = f; recording = true
            setWidgetRecording()
            if (prefs(this).getBoolean("autoscroll", false) && ScrollService.instance != null) startAutoScroll()
        } catch (e: Throwable) {
            releaseRecording(); recording = false; setWidgetIdle(); fgIdle("Tap the floating button to record")
            toast("Could not start recording: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun releaseRecording() {
        runCatching { display?.release() }; display = null
        runCatching { recorder?.release() }; recorder = null
        runCatching { projection?.stop() }; projection = null
    }

    // ---- auto-scroll ----------------------------------------------------------------------------------------------
    // Pace: a 0.6-screen swipe in 90 ms (about 0.11 screen per frame at 60 fps, a third of the 0.30 span limit the tracker is
    // tuned to), then 650 ms for the fling to settle. The recording stops after scrollcapsec seconds (default 300) and the text is read.
    private var scrolling = false; private var swipes = 0; private var scrollT0 = 0L
    private val scrollRun = Runnable { scrollStep() }
    private fun startAutoScroll() { app.vocr.core.Sfx.scroll(this, true); lastThumb = null; still = 0; scrolling = true; swipes = 0; scrollT0 = System.currentTimeMillis(); ui.postDelayed(scrollRun, 2500) }
    private fun stopAutoScroll() { app.vocr.core.Sfx.scroll(this, false); scrolling = false; ui.removeCallbacks(scrollRun) }
    private fun scrollStep() {
        val svc = ScrollService.instance
        if (!scrolling || !recording || svc == null) return
        if (System.currentTimeMillis() - scrollT0 >= prefs(this).getInt("scrollcapsec", 300) * 1000L) { stopAutoScroll(); toast("Auto-scroll done"); stopRecording(); return }
        val m = resources.displayMetrics
        svc.swipe(m.widthPixels, m.heightPixels, 0.80f, 0.20f, 90) { ok ->
            if (ok) swipes++
            ui.postDelayed({ checkBottom(svc) }, 650)   // let the fling settle, then look at the screen
        }
    }

    // Bottom of page: after each swipe compare a small grey thumbnail with the previous one. 3 swipes in a row that
    // change nothing = the page has ended, so stop and read. The time cap above stays as the safety net.
    private var lastThumb: IntArray? = null; private var still = 0
    private fun checkBottom(svc: ScrollService) {
        if (!scrolling || !recording) return
        svc.thumb { t ->
            ui.post {
                if (!scrolling || !recording) return@post
                val prev = lastThumb
                if (t != null && prev != null) {
                    var d = 0L; for (i in t.indices) d += Math.abs(t[i] - prev[i])
                    val mad = d.toDouble() / t.size
                    still = if (mad < 1.2) still + 1 else 0
                    if (still >= 3) { stopAutoScroll(); toast("Reached the bottom - reading now"); stopRecording(); return@post }
                }
                if (t != null) lastThumb = t
                ui.postDelayed(scrollRun, 50)
            }
        }
    }

    private fun stopRecording() {
        if (!recording) return
        stopAutoScroll()
        recording = false
        val f = recFile
        val ok = runCatching { recorder?.stop() }.isSuccess
        releaseRecording()
        setWidgetIdle()
        if (!ok || f == null || !f.exists() || f.length() < 1000) { fgIdle("Tap the floating button to record"); toast("Recording was too short"); return }
        val label = "Recording " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        app.vocr.core.JobQueue.addFile(applicationContext, f, label)   // joins the queue; you can record or add more right away
        toast("Added to the queue")
        ui.post { setWidgetIdle(); fgIdle("Tap the floating button to record"); refreshQueueUi() }
    }

    // ---- queue display on the widget -------------------------------------------------------------------------------
    private val chipPoll = object : Runnable {
        override fun run() {
            val c = addChip ?: return
            val q = lp
            if (q != null) { val cl = c.layoutParams as WindowManager.LayoutParams; cl.x = q.x.coerceIn(0, (screenW() - dp(40)).coerceAtLeast(0)); cl.y = (q.y - dp(46)).coerceAtLeast(0); runCatching { wm?.updateViewLayout(c, cl) } }
            ui.postDelayed(this, 300)
        }
    }

    private fun showAddChip() {
        if (addChip != null || widget == null) return
        val c = AddChip(this)
        c.setOnClickListener { showAddMenu() }
        val q = WindowManager.LayoutParams(dp(40), dp(40), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = lp?.x ?: 0; y = (lp?.y ?: dp(60)) - dp(46) }
        runCatching { wm?.addView(c, q); addChip = c; ui.postDelayed(chipPoll, 300) }
    }

    /** The (+) offers both: queue old recordings, or start a new (auto-scroll) recording while earlier ones are still being read. */
    private fun showAddMenu() {
        val q = lp ?: return
        hideMenu()
        val x = (q.x - dp(176)).coerceAtLeast(dp(4)); val y = (q.y - dp(46)).coerceAtLeast(dp(4))
        val old = chip("Add old recordings", Color.parseColor("#232A45")) {
            hideMenu(); startActivity(Intent(this, AddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val neu = chip(if (recording) "Recording now" else "New recording" + (if (prefs(this).getBoolean("autoscroll", false) && ScrollService.instance != null) " (auto-scroll)" else ""), Color.parseColor("#7C5CFF")) {
            hideMenu()
            if (recording) toast("Already recording - tap the bubble to stop")
            else startActivity(Intent(this, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        overlay(old, x, y)
        overlay(neu, x, y + dp(42))
        menu = listOf(old, neu)
        ui.postDelayed(hideMenuRun, 5000)
    }

    private fun hideAddChip() { ui.removeCallbacks(chipPoll); addChip?.let { runCatching { wm?.removeView(it) } }; addChip = null }

    private fun refreshQueueUi() {
        if (widget == null) return
        val n = app.vocr.core.JobQueue.activeCount()
        if (n > 0) { showAddChip(); addChip?.count = n; addChip?.invalidate() } else hideAddChip()
        if (!recording) {
            val j = app.vocr.core.JobQueue.current()
            if (j != null) { setRing(j.frac, j.ringLabel) } else if (n == 0) { widget?.let { it.ring = -1f; it.label = "REC"; it.invalidate() } }
        }
    }

}

/** Round floating button with a progress ring on its circumference. */
private class Bubble(ctx: Context) : View(ctx) {
    var label = "REC"; var fill = Color.parseColor("#7C5CFF"); var ring = -1f; var labelSp = 11f
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG); private val pr = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density; val w = width.toFloat(); val h = height.toFloat()
        val sw = 4f * d; pr.strokeWidth = sw
        val r = RectF(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
        pf.color = fill; c.drawOval(RectF(sw, sw, w - sw, h - sw), pf)
        if (ring >= 0f) {
            pr.color = Color.parseColor("#33FFFFFF"); c.drawOval(r, pr)
            pr.color = Color.parseColor("#9DFFB0"); c.drawArc(r, -90f, 360f * ring.coerceIn(0f, 1f), false, pr)
        }
        pt.textSize = labelSp * d
        c.drawText(label, w / 2, h / 2 - (pt.descent() + pt.ascent()) / 2, pt)
    }
}

/** Circled plus (the ADD button), shown above the widget while recordings are being read. Badge = jobs in the queue. */
private class AddChip(ctx: Context) : View(ctx) {
    var count = 0
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#232A45") }
    private val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val pb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7C5CFF") }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density; val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2
        c.drawCircle(cx, cy, w / 2 - 2 * d, pf)
        ps.strokeWidth = 2f * d; c.drawCircle(cx, cy, w / 2 - 4 * d, ps)
        ps.strokeWidth = 2.5f * d; val a = 8f * d
        c.drawLine(cx - a, cy, cx + a, cy, ps); c.drawLine(cx, cy - a, cx, cy + a, ps)
        if (count > 1) { c.drawCircle(w - 8 * d, 8 * d, 8 * d, pb); pt.textSize = 10f * d; c.drawText(count.toString(), w - 8 * d, 8 * d - (pt.descent() + pt.ascent()) / 2, pt) }
    }
}
