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
    private var readMode = false   // hands-free reading: auto-scroll without recording or queue jobs
    private fun active() = recording || readMode
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

    override fun onDestroy() { readMode = false; stopAutoScroll(); removeWidget(); releaseRecording(); super.onDestroy() }
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
        val b = Notification.Builder(this, CH_STATUS).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("ScrOllOn").setContentText(text).setOngoing(true)
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
        cover = Cover(this, wm!!, ui)
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
                    tv.pressedState = true; tv.invalidate(); tv.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
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
                    tv.pressedState = false; tv.invalidate()
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

    private var cover: Cover? = null
    private fun hideMenu() { ui.removeCallbacks(hideMenuRun); menu.forEach { v -> runCatching { wm?.removeView(v) } }; menu = emptyList() }
    private val hideMenuRun = Runnable { hideMenu() }

    private fun chip(text: String, bg: Int, onClick: () -> Unit): TextView = glassChip(this, text, bg).apply { setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onClick() } }

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
            hideMenu(); if (recording) toast("Stop recording first") else { if (readMode) stopReadMode(); closeWidget() }
        }
        fun modeText() = if (prefs(this).getBoolean("fast", false)) "Fast" else "Slow"
        val mode = chip(modeText(), Color.parseColor("#25262B")) {}
        mode.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val f = !prefs(this).getBoolean("fast", false); prefs(this).edit().putBoolean("fast", f).apply()
            mode.text = modeText(); ui.removeCallbacks(hideMenuRun); ui.postDelayed(hideMenuRun, 4000)
        }
        fun autoText() = if (prefs(this).getBoolean("autoscroll", false)) "Auto-scroll ON" else "Auto-scroll off"
        fun autoOn() = prefs(this).getBoolean("autoscroll", false) && ScrollService.instance != null
        val auto = chip(autoText(), Color.parseColor("#25262B")) {}
        fun styleAuto() {   // violet = armed, slate + dim text = off, so the state reads at a glance
            (auto.background as GradientDrawable).setColor(Color.parseColor(if (autoOn()) "#D2B08A" else "#25262B"))
            auto.setTextColor(if (autoOn()) Color.parseColor("#17120C") else Color.parseColor("#A9A6A0"))
        }
        styleAuto()
        auto.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
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
        fun rdText() = if (readMode) "Scroll: ON" else "Scroll: off"
        val rd = chip(rdText(), Color.parseColor("#25262B")) {}
        fun styleRd() {
            (rd.background as GradientDrawable).setColor(Color.parseColor(if (readMode) "#D2B08A" else "#25262B"))
            rd.setTextColor(if (readMode) Color.parseColor("#17120C") else Color.parseColor("#A9A6A0"))
        }
        styleRd()
        rd.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            if (readMode) { stopReadMode(); hideMenu() }
            else {
                startReadMode()
                if (readMode) {   // pace slider pops up under the chip, same bronze as the Settings slider; bubble tap stops
                    rd.text = rdText(); styleRd()
                    val bar = android.widget.SeekBar(this).apply {
                        max = 10; progress = speedLevel()
                        val bronze = android.content.res.ColorStateList.valueOf(Color.parseColor("#D2B08A"))
                        progressTintList = bronze; thumbTintList = bronze
                        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#55FFFFFF"))
                        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                                if (fromUser) { prefs(this@CaptureService).edit().putInt("scrollspeed", v).apply(); ui.removeCallbacks(hideMenuRun); ui.postDelayed(hideMenuRun, 4000) }
                            }
                            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { ui.removeCallbacks(hideMenuRun) }
                            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); ui.postDelayed(hideMenuRun, 4000) }
                        })
                    }
                    val pill = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(dp(14), dp(8), dp(14), dp(6))
                        background = GradientDrawable().apply { setColor(Color.parseColor("#E625262B")); cornerRadius = dp(18).toFloat() }
                        addView(android.widget.TextView(this@CaptureService).apply { text = "Pace  (10 = normal, 0 = pause)"; setTextColor(Color.parseColor("#A9A6A0")); textSize = 11f })
                        addView(bar, android.widget.LinearLayout.LayoutParams(dp(190), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
                    }
                    pill.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    // keep the whole pill on screen: the bubble sits on the right edge, so anchor from the screen's right side when needed
                    overlay(pill, (p.x - dp(6)).coerceIn(dp(4), (screenW() - pill.measuredWidth - dp(4)).coerceAtLeast(dp(4))), p.y + p.height + dp(126))
                    menu = menu + pill
                    ui.removeCallbacks(hideMenuRun); ui.postDelayed(hideMenuRun, 5000)
                } else hideMenu()
            }
        }
        overlay(auto, p.x - dp(6), p.y + p.height + dp(46))
        overlay(rd, p.x - dp(6), p.y + p.height + dp(86))
        val cv = chip("Cover", Color.parseColor("#25262B")) {
            hideMenu()
            if (recording) toast("Stop recording first") else {
                if (!prefs(this).getBoolean("cover", false)) prefs(this).edit().putBoolean("cover", true).apply()
                cover?.edit()
            }
        }
        overlay(cv, p.x - dp(6), p.y + p.height + dp(166))
        menu = listOf(close, mode, auto, rd, cv)
        ui.postDelayed(hideMenuRun, 4000)
    }

    private fun setRing(f: Float, label: String?) {
        widget?.let { it.ring = f; if (label != null) it.label = label; it.invalidate() }
    }

    private fun closeWidget() { removeWidget(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    private fun removeWidget() { cover?.remove(); app.vocr.core.JobQueue.listener = null; hideAddChip(); hideMenu(); ui.removeCallbacks(tuckRun); widget?.let { runCatching { wm?.removeView(it) } }; widget = null }

    private fun setWidgetIdle() {
        widget?.apply { label = "REC"; alpha = 1f; labelSp = 11f; fill = Color.parseColor("#3A3B42"); ring = -1f; invalidate() }
        lp?.let { it.width = dp(52); it.height = dp(52); widget?.let { w -> runCatching { wm?.updateViewLayout(w, it) } } }
        tuck(false)
    }

    private fun setWidgetRecording() {
        widget?.apply { label = "STOP"; alpha = 0.55f; labelSp = 9f; fill = Color.parseColor("#E5484D"); ring = -1f; invalidate() }
        lp?.let { it.width = dp(40); it.height = dp(40); widget?.let { w -> runCatching { wm?.updateViewLayout(w, it) } } }
        tuck(false)
    }

    private fun onWidgetTap() {
        if (readMode) stopReadMode()
        else if (recording) stopRecording()
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
            projection = mp; recorder = r; recFile = f
            val cv = cover?.takeIf { it.enabled() }
            if (cv != null) { cv.deploy(); recording = true }   // 'recording' guards the stop callbacks while the cover draws
            ui.postDelayed({
                if (cv != null && !recording) return@postDelayed
                try {
                    r.start()
                    recording = true; app.vocr.Haptics.recStart(this)
                    setWidgetRecording()
                    if (prefs(this).getBoolean("autoscroll", false) && ScrollService.instance != null) startAutoScroll()
                } catch (e: Throwable) {
                    cover?.remove(); releaseRecording(); recording = false; setWidgetIdle(); fgIdle("Tap the floating button to record")
                    toast("Could not start recording: " + (e.message ?: e.javaClass.simpleName))
                }
            }, if (cv != null) 300L else 0L)
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
    private fun startAutoScroll() { app.vocr.Haptics.autoStart(this); app.vocr.core.Sfx.scroll(this, true); lastThumb = null; still = 0; scrolling = true; swipes = 0; scrollT0 = System.currentTimeMillis(); ui.postDelayed(scrollRun, 2500) }
    private fun stopAutoScroll() { if (scrolling) app.vocr.Haptics.autoEnd(this); app.vocr.core.Sfx.scroll(this, false); scrolling = false; ui.removeCallbacks(scrollRun) }
    // Speed 0..10 (home > Settings). 10 = today's tested pace (a 90 ms swipe, then a 650 ms settle) and is the default.
    // 9 down to 1: one slow, steady drag per step (no fling), at a constant speed that falls smoothly from 0.60 screen/s at 9
    // to 0.03 screen/s at 1 (about 33 s per screen). 0 = stopped: no gestures at all, the recording just keeps running.
    private fun speedLevel() = prefs(this).getInt("scrollspeed", 10).coerceIn(0, 10)
    /** Screens per second for levels 1..9: geometric, x0.69 per step. */
    private fun crawlSpeed(l: Int): Double = 0.60 * Math.pow(0.03 / 0.60, (9 - l) / 8.0)
    // The safety cap stretches with the slower pace so a slow crawl still reaches a similar depth, never more than 30 minutes.
    private fun capMs(): Long { val l = speedLevel(); val base = prefs(this).getInt("scrollcapsec", 300) * 1000L
        return if (l >= 10) base else minOf(1_800_000L, maxOf(base, (base * 0.6 / crawlSpeed(l.coerceAtLeast(1))).toLong())) }
    private var pausedSince = 0L
    private fun scrollStep() {
        val svc = ScrollService.instance
        if (!scrolling || !active() || svc == null) return
        val lvl = speedLevel()
        if (lvl == 0) {   // stopped: wait, and do not let the time cap run while paused
            if (pausedSince == 0L) pausedSince = System.currentTimeMillis()
            ui.postDelayed(scrollRun, 400); return
        }
        if (pausedSince != 0L) { scrollT0 += System.currentTimeMillis() - pausedSince; pausedSince = 0L; still = 0 }
        if (System.currentTimeMillis() - scrollT0 >= capMs()) { endScroll("Auto-scroll done"); return }
        val m = resources.displayMetrics
        if (lvl >= 10) {
            svc.swipe(m.widthPixels, m.heightPixels, 0.70f, 0.10f, 90, startX()) { ok ->
                if (ok) swipes++
                app.vocr.Haptics.autoPulse(this)
                ui.postDelayed({ checkBottom(svc) }, 650L)   // let the fling settle, then look at the screen
            }
        } else {
            val v = crawlSpeed(lvl)
            val dist = (v * 4.0).coerceIn(0.2, 0.6)           // screens per drag
            val dur = (dist / v * 1000).toLong()               // constant speed, no fling at the lift
            for (k in 1..(dur / 1500L).toInt()) ui.postDelayed({ if (scrolling && active() && speedLevel() > 0) app.vocr.Haptics.autoPulse(this) }, k * 1500L)   // gentle pulse every ~1.5 s during a slow drag
            svc.swipe(m.widthPixels, m.heightPixels, 0.70f, (0.70 - dist).toFloat(), dur, startX()) { ok ->
                if (ok) swipes++
                ui.postDelayed({ checkBottom(svc) }, 120L)
            }
        }
    }

    // Bottom of page: after each swipe compare a small grey thumbnail with the previous one. 3 swipes in a row that
    // change nothing = the page has ended, so stop and read. The time cap above stays as the safety net.
    private val STUCK_RETRIES = 3; private val STUCK_FIRST_PAUSE_S = 4L; private val STUCK_STEP_S = 2L   // user spec Oct 14
    /** Each retry after a no-change swipe starts in a different column (35%, 20%, 75%, 55% of the width, always at 70% height), so a floating button or
     *  overlay under one start point cannot make a blocked swipe look like "bottom reached". The recording only ends when ALL four columns changed nothing. */
    private fun startX(): Float {
        val c = floatArrayOf(ScrollService.X0, 0.20f, 0.75f, 0.55f); val i = still.coerceIn(0, 3)
        val m = resources.displayMetrics; val y = (0.70f * m.heightPixels).toInt(); val bl = cover?.blocked() ?: emptyList()
        // first candidate (starting at the rotation's pick) whose start point is not under the cover
        for (k in 0..3) { val x = c[(i + k) % 4]; if (bl.none { it.contains((x * m.widthPixels).toInt(), y) }) return x }
        return c[i]
    }
    private var lastThumb: IntArray? = null; private var still = 0
    private fun checkBottom(svc: ScrollService) {
        if (!scrolling || !active()) return
        svc.thumb { t ->
            ui.post {
                if (!scrolling || !active()) return@post
                val prev = lastThumb
                if (t != null && prev != null) {
                    var d = 0L; for (i in t.indices) d += Math.abs(t[i] - prev[i])
                    val mad = d.toDouble() / t.size
                    still = if (mad < 1.2) still + 1 else 0
                    // No change after a swipe: the page may just be slow to load. Let go, wait 4 s, try again; each wait is 2 s longer
                    // (4, 6, 8 s). If the third retry still changes nothing, it is the bottom: end the recording.
                    if (still > STUCK_RETRIES) { endScroll(if (readMode) "Reached the bottom" else "Reached the bottom - reading now"); return@post }
                    if (still > 0) {
                        val pause = (STUCK_FIRST_PAUSE_S + STUCK_STEP_S * (still - 1)) * 1000L
                        scrollT0 += pause   // waiting for the page does not count against the time cap
                        ui.postDelayed(scrollRun, pause); return@post
                    }
                }
                if (t != null) lastThumb = t
                ui.postDelayed(scrollRun, 50)
            }
        }
    }

    /** The page ended or the time cap hit: stop the recording (and read it) or, in hands-free mode, just stop scrolling. */
    private fun endScroll(msg: String) { if (readMode) { toast(msg); stopReadMode() } else { stopAutoScroll(); toast(msg); stopRecording() } }

    /** Hands-free reading: auto-scroll with NO recording, no capture and no queue job. Tap the bubble to stop. */
    private fun startReadMode() {
        if (recording) { toast("Stop recording first"); return }
        if (readMode) { toast("Already scrolling - tap the bubble to stop"); return }
        if (ScrollService.instance == null) { toast("Turn on Auto-scroll in ScrOllOn Settings first"); return }
        readMode = true; pausedSince = 0L
        widget?.apply { label = "STOP"; alpha = 0.7f; labelSp = 9f; fill = Color.parseColor("#3A3B42"); ring = -1f; invalidate() }
        lp?.let { it.width = dp(40); it.height = dp(40); widget?.let { w -> runCatching { wm?.updateViewLayout(w, it) } } }
        tuck(false)
        startAutoScroll()
    }
    private fun stopReadMode() { if (!readMode) return; readMode = false; stopAutoScroll(); setWidgetIdle(); ui.post { refreshQueueUi() } }

    private fun stopRecording() {
        if (!recording) return
        stopAutoScroll()
        recording = false
        cover?.remove()
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
        c.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); showAddMenu() }
        val q = WindowManager.LayoutParams(dp(40), dp(40), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = lp?.x ?: 0; y = (lp?.y ?: dp(60)) - dp(46) }
        runCatching { wm?.addView(c, q); addChip = c; ui.postDelayed(chipPoll, 300) }
    }

    /** The (+) offers both: queue old recordings, or start a new (auto-scroll) recording while earlier ones are still being read. */
    private fun showAddMenu() {
        val q = lp ?: return
        hideMenu()
        val x = (q.x - dp(176)).coerceAtLeast(dp(4)); val y = (q.y - dp(46)).coerceAtLeast(dp(4))
        val old = chip("Add old recordings", Color.parseColor("#25262B")) {
            hideMenu(); startActivity(Intent(this, AddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val neu = chip(if (recording) "Recording now" else "New recording" + (if (prefs(this).getBoolean("autoscroll", false) && ScrollService.instance != null) " (auto-scroll)" else ""), Color.parseColor("#D2B08A")) {
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
        if (!recording && !readMode) {
            val j = app.vocr.core.JobQueue.current()
            if (j != null) { setRing(j.frac, j.ringLabel) } else if (n == 0) { widget?.let { it.ring = -1f; it.label = "REC"; it.invalidate() } }
        }
    }

}
