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
import android.graphics.Bitmap
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

    // FLAG_SECURE keeps our overlays out of recordings, but a secure window also blocks the user's own screenshots. So the bubble and menus
    // are secure only while a recording starts or runs, or auto-scroll runs. When idle, screenshots work.
    private var starting = false
    private fun wantSecure() = starting || recording || scrolling || readMode
    private fun secureFlag() = if (wantSecure()) WindowManager.LayoutParams.FLAG_SECURE else 0
    private fun syncSecure() {
        val apply = Runnable { runCatching {
            val want = wantSecure()
            fun sync(v: View?, p: WindowManager.LayoutParams?) {
                if (v == null || p == null) return
                val has = (p.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
                if (want != has) {
                    p.flags = if (want) p.flags or WindowManager.LayoutParams.FLAG_SECURE else p.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    runCatching { wm?.updateViewLayout(v, p) }
                }
            }
            sync(widget, lp)
            // menus and the pace bar created while idle must follow the same rule once a take or Scroll starts
            (menu + listOfNotNull(pace, addChip, fbar)).forEach { v -> sync(v, v.layoutParams as? WindowManager.LayoutParams) }
        } }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) apply.run() else ui.post(apply)
    }
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
        val b = Notification.Builder(this, CH_STATUS).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("ScrollRSI").setContentText(text).setOngoing(true)
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
        if (app.vocr.VocrApp.reducedMotion()) { p.x = x; runCatching { wm?.updateViewLayout(w, p) }; return }
        android.animation.ValueAnimator.ofInt(p.x, x).apply {
            duration = 180
            addUpdateListener { p.x = it.animatedValue as Int; runCatching { wm?.updateViewLayout(w, p) }; syncShotBubble() }
            start()
        }
    }

    /** Collapse to a thin edge sliver (or pull back out). */
    private fun tuck(on: Boolean) {
        ui.removeCallbacks(tuckRun)
        if (on && busy) return   // stay visible while reading so the progress ring can be seen
        tucked = on
        widget?.alpha = if (on) 0.6f else if (recording) 0.55f else 1f
        syncShotBubble()
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or secureFlag(), PixelFormat.TRANSLUCENT).apply {
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
                    if (moved) { p.x = ox + (e.rawX - sx).toInt(); p.y = oy + (e.rawY - sy).toInt(); wm?.updateViewLayout(tv, p); syncShotBubble() }
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
        runCatching { wm?.addView(tv, p) }
        ui.postDelayed(shotPoll, 400)
        ui.postDelayed(tuckRun, 3000)
    }

    private var cover: Cover? = null
    private var pace: View? = null
    private fun hidePace() { pace?.let { v -> runCatching { wm?.removeView(v) } }; pace = null }
    /** The menu (chips and its pace bar) goes after a few seconds. While Scroll is ON the speed lives in the floating bar instead. */
    private fun hideMenu() { ui.removeCallbacks(hideMenuRun); menu.forEach { v -> runCatching { wm?.removeView(v) } }; menu = emptyList(); hidePace() }
    private fun showPace(p: WindowManager.LayoutParams) {
        hidePace()
        val bar = android.widget.SeekBar(this).apply {
            max = 10; progress = speedLevel()
            val bronze = android.content.res.ColorStateList.valueOf(accentC())
            progressTintList = bronze; thumbTintList = bronze
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#55FFFFFF"))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                    sb?.let { androidx.core.view.ViewCompat.setStateDescription(it, "$v of 10") }
                    if (fromUser) { prefs(this@CaptureService).edit().putInt("scrollspeed", v).apply(); ui.removeCallbacks(hideMenuRun); ui.postDelayed(hideMenuRun, 4000) }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { ui.removeCallbacks(hideMenuRun) }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { sb?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); ui.postDelayed(hideMenuRun, 4000) }
            })
        }
        val pill = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(6))
            background = GradientDrawable().apply { setColor(if (app.vocr.Pal.paper) Color.parseColor("#EBF9F6F0") else Color.parseColor("#E625262B")); cornerRadius = dp(18).toFloat() }
            addView(android.widget.TextView(this@CaptureService).apply { text = "Pace  (10 = normal, 0 = pause)"; setTextColor(mutedC()); textSize = 11f })
            addView(bar, android.widget.LinearLayout.LayoutParams(dp(190), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            if (canPause()) {   // a take can be paused from the menu
                val pb = chip(pauseLabel(), chipC()) { togglePause(); ui.removeCallbacks(hideMenuRun); ui.postDelayed(hideMenuRun, 4000) }
                pb.contentDescription = if (paused) "Resume. Continues scrolling and recording" else "Pause. Stops scrolling and recording"
                pauseBtns = pauseBtns + pb
                addView(pb, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
            }
        }
        pill.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        // keep the whole pill on screen: the bubble sits on the right edge, so anchor from the screen's right side when needed
        overlay(pill, (p.x - dp(6)).coerceIn(dp(4), (screenW() - pill.measuredWidth - dp(4)).coerceAtLeast(dp(4))), p.y + p.height + dp(126))
        pace = pill
    }
    private val hideMenuRun = Runnable { hideMenu() }

    // ---- floating speed bar (Scroll ON only): draggable, folds to an edge handle after 3 s, stays until Scroll ends ------------------
    private var fbar: View? = null
    private var fbarLp: WindowManager.LayoutParams? = null
    private var fbarFolded = false
    private var fbarRight = true
    private val foldRun = Runnable { foldFloatBar(true) }
    private fun hideFloatBar() { ui.removeCallbacks(foldRun); fbar?.let { v -> runCatching { wm?.removeView(v) } }; fbar = null; fbarLp = null }
    private fun fbarOpenX(): Int { val w = fbar?.measuredWidth ?: dp(220); return if (fbarRight) screenW() - w - dp(6) else dp(6) }
    private fun fbarFoldX(): Int { val w = fbar?.measuredWidth ?: dp(220); return if (fbarRight) screenW() - dp(18) else -(w - dp(18)) }
    private fun slideFloat(x: Int) {
        val q = fbarLp ?: return; val v = fbar ?: return
        if (app.vocr.VocrApp.reducedMotion()) { q.x = x; runCatching { wm?.updateViewLayout(v, q) }; return }
        android.animation.ValueAnimator.ofInt(q.x, x).apply {
            duration = 180
            addUpdateListener { q.x = it.animatedValue as Int; runCatching { wm?.updateViewLayout(v, q) } }
            start()
        }
    }
    private fun foldFloatBar(on: Boolean) {
        ui.removeCallbacks(foldRun)
        val v = fbar ?: return
        fbarFolded = on
        v.alpha = if (on) 0.65f else 1f
        slideFloat(if (on) fbarFoldX() else fbarOpenX())
        if (!on) ui.postDelayed(foldRun, 3000)
    }
    private fun showFloatBar() {
        hideFloatBar()
        val pr = prefs(this)
        val bar = android.widget.SeekBar(this).apply {
            max = 10; progress = speedLevel()
            val bronze = android.content.res.ColorStateList.valueOf(accentC())
            progressTintList = bronze; thumbTintList = bronze
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#55FFFFFF"))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                    sb?.let { androidx.core.view.ViewCompat.setStateDescription(it, "$v of 10") }
                    if (fromUser) { pr.edit().putInt("scrollspeed", v).apply(); ui.removeCallbacks(foldRun) }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { ui.removeCallbacks(foldRun) }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { sb?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); ui.removeCallbacks(foldRun); ui.postDelayed(foldRun, 3000) }
            })
        }
        val pill = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(6))
            background = GradientDrawable().apply { setColor(if (app.vocr.Pal.paper) Color.parseColor("#EBF9F6F0") else Color.parseColor("#E625262B")); cornerRadius = dp(18).toFloat() }
            addView(android.widget.TextView(this@CaptureService).apply { text = "Pace  (10 = normal, 0 = pause)  \u2630"; setTextColor(mutedC()); textSize = 11f })
            addView(bar, android.widget.LinearLayout.LayoutParams(dp(190), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            val pb = chip(pauseLabel(), chipC()) { togglePause(); ui.removeCallbacks(foldRun); ui.postDelayed(foldRun, 3000) }
            pb.contentDescription = "Pause. Stops scrolling"
            pauseBtns = pauseBtns + pb
            addView(pb, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
        }
        pill.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        fbarRight = if (pr.contains("fbar_right")) pr.getBoolean("fbar_right", true) else sideRight
        val b = lp
        val maxY = (resources.displayMetrics.heightPixels - pill.measuredHeight - dp(48)).coerceAtLeast(dp(40))
        val y0 = (if (pr.contains("fbar_y")) pr.getInt("fbar_y", dp(300)) else (b?.y ?: dp(220)) + dp(70)).coerceIn(dp(40), maxY)
        val q = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or secureFlag(), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = y0 }
        fbar = pill; fbarLp = q; fbarFolded = false
        q.x = fbarOpenX()
        // drag anywhere except the slider; a touch on the folded handle slides the pill back out
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var wasFolded = false
        pill.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ui.removeCallbacks(foldRun); sx = e.rawX; sy = e.rawY; ox = q.x; oy = q.y; moved = false; wasFolded = fbarFolded
                    if (fbarFolded) foldFloatBar(false)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - sx) > dp(6) || Math.abs(e.rawY - sy) > dp(6)) moved = true
                    if (moved) {
                        q.x = ox + (e.rawX - sx).toInt(); q.y = (oy + (e.rawY - sy).toInt()).coerceIn(dp(40), maxY)
                        runCatching { wm?.updateViewLayout(pill, q) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        fbarRight = q.x + pill.measuredWidth / 2 > screenW() / 2
                        pr.edit().putBoolean("fbar_right", fbarRight).putInt("fbar_y", q.y).apply()
                        fbarFolded = false; slideFloat(fbarOpenX()); ui.postDelayed(foldRun, 3000)
                    } else if (!wasFolded) ui.postDelayed(foldRun, 3000)
                }
            }
            true
        }
        runCatching { wm?.addView(pill, q) }
        ui.postDelayed(foldRun, 3000)
    }

    private fun chip(text: String, bg: Int, onClick: () -> Unit): TextView = glassChip(this, text, bg).apply { setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onClick() } }

    private fun overlay(v: View, x0: Int, y0: Int) {
        // measure and keep the whole chip on screen (the bubble often sits at an edge)
        v.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val x = x0.coerceIn(dp(4), (screenW() - v.measuredWidth - dp(4)).coerceAtLeast(dp(4)))
        val y = y0.coerceAtLeast(dp(4))
        val q = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or secureFlag(), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
        runCatching { wm?.addView(v, q) }
    }

    /** Long press: a close X at the top-left corner of the bubble and a Fast/Slow switch below it. */
    private fun showMenu() {
        val p = lp ?: return
        hideMenu(); tuck(false)
        val close = chip("\u00D7", Color.parseColor("#E5484D")) {
            hideMenu(); if (recording) toast("Stop recording first") else { if (readMode) stopReadMode(); closeWidget() }
        }
        close.contentDescription = "Close the floating button"
        fun modeText() = if (prefs(this).getBoolean("fast", false)) "Fast" else "Slow"
        val mode = chip(modeText(), chipC()) {}
        mode.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val f = !prefs(this).getBoolean("fast", false); prefs(this).edit().putBoolean("fast", f).apply()
            mode.text = modeText(); ui.removeCallbacks(hideMenuRun); ui.postDelayed(hideMenuRun, 4000)
        }
        fun autoText() = if (prefs(this).getBoolean("autoscroll", false)) "Auto-scroll ON" else "Auto-scroll off"
        fun autoOn() = prefs(this).getBoolean("autoscroll", false) && ScrollService.instance != null
        val auto = chip(autoText(), chipC()) {}
        fun styleAuto() {   // violet = armed, slate + dim text = off, so the state reads at a glance
            (auto.background as GradientDrawable).setColor(Color.parseColor(if (autoOn()) accentHex() else chipHex()))
            auto.setTextColor(if (autoOn()) onAccentC() else mutedC())
        }
        styleAuto()
        auto.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            if (ScrollService.instance == null) {
                if (ScrollService.enabledInSettings(this)) toast("Accessibility shows ON but Android has stopped the service. Switch ScrollRSI off and on again.")
                else toast("Auto-scroll needs Accessibility. Read the notice first.")
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
        val rd = chip(rdText(), chipC()) {}
        fun styleRd() {
            (rd.background as GradientDrawable).setColor(Color.parseColor(if (readMode) accentHex() else chipHex()))
            rd.setTextColor(if (readMode) onAccentC() else mutedC())
        }
        styleRd()
        rd.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            if (readMode) { stopReadMode(); hideMenu() }
            else {
                startReadMode()
                hideMenu()   // the floating speed bar takes over while Scroll is ON
            }
        }
        overlay(auto, p.x - dp(6), p.y + p.height + dp(46))
        overlay(rd, p.x - dp(6), p.y + p.height + dp(86))
        fun stText() = if (prefs(this).getBoolean("shot_text", false)) "Shot text: ON" else "Shot text: off"
        val stChip = chip(stText(), chipC()) {}
        fun styleSt() {
            val on = prefs(this).getBoolean("shot_text", false)
            (stChip.background as GradientDrawable).setColor(Color.parseColor(if (on) accentHex() else chipHex()))
            stChip.setTextColor(if (on) onAccentC() else mutedC())
        }
        styleSt()
        stChip.contentDescription = "Save the text of a screenshot next to the image"
        stChip.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val a = !prefs(this).getBoolean("shot_text", false); prefs(this).edit().putBoolean("shot_text", a).apply()
            stChip.text = stText(); styleSt(); ui.removeCallbacks(hideMenuRun); ui.postDelayed(hideMenuRun, 4000)
        }
        overlay(stChip, p.x - dp(6), p.y + p.height + dp(126))
        if (!readMode) showPace(p)   // in Scroll mode the speed lives in the floating bar
        // Cover is parked (Cover.AVAILABLE = false): no menu entry for now.
        val cv: TextView? = if (Cover.AVAILABLE) {
        val c = chip("Cover", chipC()) {
            hideMenu()
            if (recording) toast("Stop recording first") else {
                if (!prefs(this).getBoolean("cover", false)) prefs(this).edit().putBoolean("cover", true).apply()
                cover?.edit()
            }
        }
        overlay(c, p.x - dp(6), p.y + p.height + dp(166)); c
        } else null
        menu = listOfNotNull(close, mode, auto, rd, stChip, cv)
        ui.postDelayed(hideMenuRun, 4000)
    }

    private fun setRing(f: Float, label: String?) {
        widget?.let { it.ring = f; if (label != null) it.label = label; it.invalidate() }
    }

    private fun closeWidget() { removeWidget(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    private fun removeWidget() { ui.removeCallbacks(shotPoll); shotBubble?.let { v -> runCatching { wm?.removeView(v) } }; shotBubble = null; hidePace(); hideFloatBar(); hideShotBar(); shotSession?.discard(); shotSession = null; cover?.remove(); app.vocr.core.JobQueue.listener = null; hideAddChip(); hideMenu(); ui.removeCallbacks(tuckRun); widget?.let { runCatching { wm?.removeView(it) } }; widget = null }

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


    // ---- screenshot and long (scrolling) screenshot ----------------------------------------------------------------
    // Tap Screenshot: one full-resolution PNG in Download/ScrollRSI, then a "Scroll capture" button appears above the nav bar.
    // Hold it (or tap it for one page) to scroll the page and extend the picture; 2 s after release the long image is saved.
    private var shotBar: View? = null
    private var shotSession: LongShotSession? = null
    private var shotSaved = false          // the first capture is already saved as a plain screenshot
    private var shotHolding = false
    @Volatile private var shotRunning = false
    private val shotExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val shotIdleRun = Runnable { finishLong() }

    private fun shotViews(): List<View?> = listOf<View?>(widget, pace, addChip, fbar, shotBar, shotBubble) + menu

    /** Hides every overlay of ours for the instant of the capture, then restores it. Alpha keeps the touch stream alive. */
    @Volatile private var hideHeld = false
    private var heldViews: List<View> = emptyList(); private var heldAlpha: List<Float> = emptyList()
    /** One blink per cycle: hide our overlays once, take every capture of the cycle (settle checks included), then show them again. */
    private fun holdHidden(on: Boolean) {
        val l = java.util.concurrent.CountDownLatch(1)
        ui.post {
            if (on) { heldViews = shotViews().filterNotNull(); heldAlpha = heldViews.map { it.alpha }; heldViews.forEach { it.alpha = 0f }; hideHeld = true }
            else { hideHeld = false; heldViews.forEachIndexed { i, v -> v.alpha = heldAlpha[i] }; heldViews = emptyList() }
            l.countDown()
        }
        l.await(2, java.util.concurrent.TimeUnit.SECONDS)
        if (on) Thread.sleep(160)
    }

    private fun snap(done: (Bitmap?) -> Unit) {
        if (hideHeld) { val svc = ScrollService.instance; if (svc == null) done(null) else svc.fullShot { bm -> ui.post { done(bm) } }; return }
        ui.post {
            val vs = shotViews().filterNotNull(); val saved = vs.map { it.alpha }
            vs.forEach { it.alpha = 0f }
            ui.postDelayed({
                val svc = ScrollService.instance
                if (svc == null) { vs.forEachIndexed { i, v -> v.alpha = saved[i] }; done(null) }
                else svc.fullShot { bm -> ui.post { vs.forEachIndexed { i, v -> v.alpha = saved[i] }; done(bm) } }
            }, 160)
        }
    }

    private fun snapBlocking(): Bitmap? {
        val l = java.util.concurrent.CountDownLatch(1); var r: Bitmap? = null
        snap { r = it; l.countDown() }
        l.await(8, java.util.concurrent.TimeUnit.SECONDS)
        return r
    }

    private fun stamp() = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.US).format(java.util.Date())

    private fun startShot(save: Boolean) {
        if (recording || starting) { toast("Stop recording first"); return }
        if (shotRunning || shotSession != null) { toast("A screenshot is already open"); return }
        if (ScrollService.instance == null) {
            toast("Screenshots need Accessibility. Read the notice first.")
            app.vocr.DisclosureActivity.openAccessibility(this); return
        }
        if (android.os.Build.VERSION.SDK_INT < 30) { toast("Screenshots need Android 11 or newer"); return }
        shotRunning = true
        snap { bm ->
            if (bm == null) { shotRunning = false; toast("Could not take the screenshot"); return@snap }
            if (LongShotSession.blank(bm)) { shotRunning = false; bm.recycle(); toast("This app blocks screenshots (the picture is black)"); return@snap }
            shotStamp = stamp(); shotEndReason = ""
            val base = "ScrollRSI shot " + shotStamp
            shotSaved = save
            if (save) shotExec.execute {
                try {
                    val f = java.io.File(cacheDir, "shot_${System.nanoTime()}_0.png")
                    f.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val n = ShotSaver.save(this, base, listOf(f), prefs(this).getBoolean("shot_text", false))
                    firstShot = n   // no toast or pop-up now: they would end up inside the next capture
                } catch (e: Throwable) { toast("Could not save: " + (e.message ?: e.javaClass.simpleName)); shotSaved = false }
            }
            shotSession = LongShotSession(this, bm, sysBar("status_bar_height"), sysBar("navigation_bar_height")); shotY0 = 0.80f; shotY1 = 0.65f; shotProbed = false; shotLoopRan = false; shotRatio = 1.0f; shotReqPx = 0f
            shotRunning = false
            showShotBar()
            ui.removeCallbacks(shotIdleRun); ui.postDelayed(shotIdleRun, 7000)   // nothing pressed: the bar goes away
        }
    }

    private fun showShotBar() {
        hideShotBar()
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sc = glassChip(this, "\u2B07 Scroll capture", accentC()).apply {
            contentDescription = "Scroll capture. Tap to scroll and capture by itself until you tap Done, or hold to scroll while held."
            setTextColor(onAccentC())
            var downAt = 0L; var ignoreDown = false; var holdFired = false
            val holdRun = Runnable { holdFired = true; shotHolding = true; startLongLoop() }   // a press longer than 350 ms scrolls while held
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                        ui.removeCallbacks(shotIdleRun)
                        holdFired = false
                        if (shotAuto) { ignoreDown = true; (v as? TextView)?.let { t -> t.text = "\u2B07 Running... tap Done to stop"; ui.postDelayed({ if (shotAuto) t.text = "\u2B07 Auto scroll running" }, 1500) } }   // a tap while the run goes on is not dead: it answers with a buzz and a label
                        else { ignoreDown = false; downAt = System.currentTimeMillis(); ui.postDelayed(holdRun, 350) }   // nothing starts until the finger lifts or 350 ms pass, so the swipe can't cancel this touch
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (ignoreDown) { ignoreDown = false; return@setOnTouchListener true }
                        ui.removeCallbacks(holdRun)
                        if (holdFired) { holdFired = false; shotHolding = false; ui.removeCallbacks(shotIdleRun); if (!shotAuto) ui.postDelayed(shotIdleRun, 2000) }   // released: 2 s to press again, then it saves
                        else {
                            shotAuto = true   // one tap = keep going by itself (no toast: it would be captured)
                            (v as? TextView)?.text = "\u2B07 Auto scroll running"
                            startLongLoop()
                        }
                        true
                    }
                    else -> true
                }
            }
        }
        val done = glassChip(this, "Done", chipC()).apply {
            contentDescription = "Save the capture now"
            setOnClickListener { shotAuto = false; ui.removeCallbacks(shotIdleRun); finishLong() }
        }
        row.addView(sc); row.addView(android.widget.Space(this), android.widget.LinearLayout.LayoutParams(dp(8), 1)); row.addView(done)
        row.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val q = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenW() - row.measuredWidth) / 2).coerceAtLeast(dp(4)); y = resources.displayMetrics.heightPixels - dp(150)
        }
        runCatching { wm?.addView(row, q); shotBar = row }
    }

    /** After a run stopped (end of page or a failure) what was captured is saved; this keeps one bubble to start a fresh long shot from where the page is now. */
    private fun showAgainBar() {
        hideShotBar()
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val again = glassChip(this, "\u21BB Run again", accentC()).apply {
            setTextColor(onAccentC()); contentDescription = "Start a new long screenshot from the current screen"
            setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING); ui.removeCallbacks(againGone); hideShotBar(); startShot(save = true) }
        }
        val close = glassChip(this, "Close", chipC()).apply { setOnClickListener { ui.removeCallbacks(againGone); hideShotBar() } }
        row.addView(again); row.addView(android.widget.Space(this), android.widget.LinearLayout.LayoutParams(dp(8), 1)); row.addView(close)
        row.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val q = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenW() - row.measuredWidth) / 2).coerceAtLeast(dp(4)); y = resources.displayMetrics.heightPixels - dp(150)
        }
        runCatching { wm?.addView(row, q); shotBar = row; ui.removeCallbacks(againGone); ui.postDelayed(againGone, 30000) }
    }
    private val againGone = Runnable { if (shotSession == null) hideShotBar() }

    private fun hideShotBar() { shotBar?.let { v -> runCatching { wm?.removeView(v) } }; shotBar = null }

    @Volatile private var shotAuto = false
    @Volatile private var shotQueued = false   // a tap that arrives while a page is still being captured waits for its turn
    private fun startLongLoop() {
        if (shotRunning) return
        shotRunning = true
        shotExec.execute { try { longLoop() } finally { shotRunning = false } }
    }

    /** Numbers only (offsets, scores, row counts, statuses): no screen content. Saved next to the images so a failed capture can be explained. */
    @Volatile private var shotEndReason = ""
    @Volatile private var shotStamp = ""
    private fun buildLabel() = runCatching { val i = packageManager.getPackageInfo(packageName, 0); i.longVersionCode.toString() + " (" + i.versionName + ")" }.getOrDefault("unknown")
    private fun shotLogText(s: LongShotSession, reason: String): String {
        val h = resources.displayMetrics.heightPixels
        return "ScrollRSI shot log \nbuild " + buildLabel() + "\nresult: " + (if (reason.isEmpty()) "finished normally" else reason) + "\nimages: " + s.frames + " captured, " + s.gaps + " gap(s)\nscreen " + screenW() + "x" + h + " swipe " + shotY0 + " to " + shotY1 + " probed=" + shotProbed + " sysTop=" + sysBar("status_bar_height") + " sysBottom=" + sysBar("navigation_bar_height") + "\n" + s.logText()
    }

    private fun sysBar(name: String): Int = runCatching { val id = resources.getIdentifier(name, "dimen", "android"); if (id > 0) resources.getDimensionPixelSize(id) else 0 }.getOrDefault(0)
    // the first swipe is a short probe: it shows which rows move (a split-screen window, headers, a composer), then the swipe is sized to that moving area
    @Volatile private var shotY0 = 0.80f
    @Volatile private var shotY1 = 0.65f
    @Volatile private var shotProbed = false
    @Volatile private var shotLoopRan = false

    // The page does not move exactly as far as the finger (flings add distance). After every join the real distance is compared with
    // the requested one, and the next drag is sized so the page moves about 55% of the moving area. Constant finger speed keeps that ratio steady.
    @Volatile private var shotX = ScrollService.X0
    @Volatile private var shotRatio = 1.0f
    @Volatile private var shotReqPx = 0f
    @Volatile private var shotSince = 0f      // estimated page movement (px) since the last accepted capture
    @Volatile private var shotBack = false    // next drag goes back (finger down) to restore the overlap
    @Volatile private var shotBackMode = false
    @Volatile private var shotLonger = false  // next drag is the longer retry (the page did not move)
    private fun sizeSwipe(s: LongShotSession) {
        val hPx = resources.displayMetrics.heightPixels.toFloat()
        val band = hPx - s.lastHdr - s.lastFtr
        if (band < hPx * 0.3f) return
        if (shotReqPx > 0f && s.lastDy > 0) {
            val r = (s.lastDy / shotReqPx).coerceIn(0.4f, 2.5f)
            shotRatio = if (shotProbed) 0.5f * shotRatio + 0.5f * r else r
        }
        val want = band * 0.55f
        val dist = (want / shotRatio).coerceIn(band * 0.15f, band * 0.90f)
        val mid = s.lastHdr + band * 0.5f
        shotY0 = ((mid + dist / 2f) / hPx).coerceIn(0.05f, 0.97f); shotY1 = ((mid - dist / 2f) / hPx).coerceIn(0.03f, 0.95f)
        shotProbed = true
        s.logLine("calibrated: ratio=${"%.2f".format(shotRatio)} (moved ${s.lastDy}px for ${"%.0f".format(shotReqPx)}px asked) next drag ${"%.2f".format(shotY0)}->${"%.2f".format(shotY1)}")
    }

    private fun swipeBlocking(): Boolean {
        val svc = ScrollService.instance ?: return false
        val hPx = resources.displayMetrics.heightPixels
        val l = java.util.concurrent.CountDownLatch(1); var ok = false
        val req = Math.abs(shotY0 - shotY1) * hPx
        val dur = (req / 0.78f).toLong().coerceIn(300L, 2500L)   // about 0.78 px per ms, the same finger speed every time
        if (shotBackMode) { shotSince = (shotSince - req * shotRatio).coerceAtLeast(0f); shotReqPx = 0f }   // a drag back is not used to calibrate the ratio
        else { shotSince += req * shotRatio; shotReqPx = req }
        svc.swipe(screenW(), hPx, shotY0, shotY1, dur, xFrac = shotX, tailMs = 160L) { ok = it; l.countDown() }
        l.await(dur / 1000 + 6, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    /**
     * One cycle = 1) screenshot (the bubbles blink once while it is taken, settle checks included), join it, 2) scroll up about 55% of the
     * moving area. Repeats while auto is on or the button is held. The very first capture of a run is compared with the one already taken.
     */
    private fun longLoop() {
        val s = shotSession ?: return
        var ends = 0; var mmRound = 0; var reason = ""; var first = true; var firstCycle = true
        var afterBack = false; var backEnds = 0; var sawMm = false
        fun endReasonText(frames: Int) = if (frames > 1) "End of the page" else if (sawMm) "Stopped: the page changed but could not be lined up, and then stopped moving. Saved what was captured" else "Stopped: the page did not scroll (swipe ignored or a fixed screen?)"
        while ((first || shotHolding || shotQueued || shotAuto) && reason.isEmpty()) {
            first = false; shotQueued = false
            shotLoopRan = true
            val t0 = System.currentTimeMillis()
            // ---- 1) screenshot, blink while it is taken
            holdHidden(true)
            var a: Bitmap? = null; var tries = 0; var settled = false
            try {
                a = snapBlocking()
                if (a != null) while (tries < 8) {   // wait until two captures in a row match: no fling still moving
                    Thread.sleep(220)
                    val b = snapBlocking() ?: break
                    if (LongShotSession.same(a!!, b)) { a.recycle(); a = b; settled = true; break }
                    a.recycle(); a = b; tries++
                }
            } finally { holdHidden(false) }
            if (a == null) { reason = "A screenshot failed"; break }
            s.logLine("capture settle tries=$tries settled=$settled after ${System.currentTimeMillis() - t0} ms")
            if (firstCycle && s.sameAsLast(a)) { a.recycle(); s.logLine("first capture equals the one already taken") }
            else { val wasBack = afterBack; afterBack = false; when (s.addFrame(a)) {
                LongShotSession.Outcome.ADDED -> { ends = 0; backEnds = 0; mmRound = 0; shotSince = 0f; shotLonger = false; sizeSwipe(s) }
                LongShotSession.Outcome.END -> { a.recycle(); if (wasBack && backEnds < 3) { backEnds++; shotLonger = true; s.logLine("same as the last accepted capture right after a drag back: the page is back where it was, not the end. Longer forward drag, try $backEnds of 3") } else if (++ends >= 3) reason = endReasonText(s.frames) }
                LongShotSession.Outcome.MISMATCH -> {
                    sawMm = true
                    a.recycle()
                    // a toast or a heads-up notification may have been on screen: wait for it to go, capture the same page again (up to 3 tries)
                    var tries2 = 0; var ok = false
                    while (tries2 < 3 && !ok && reason.isEmpty()) {
                        tries2++; Thread.sleep(2000)
                        val c = snapBlocking() ?: continue
                        when (s.addFrame(c)) {
                            LongShotSession.Outcome.ADDED -> { ok = true; mmRound = 0; shotSince = 0f; shotLonger = false; sizeSwipe(s) }
                            LongShotSession.Outcome.END -> { c.recycle(); ok = true; if (wasBack && backEnds < 3) { backEnds++; shotLonger = true; s.logLine("same as the last accepted capture right after a drag back: not the end, try $backEnds of 3") } else if (++ends >= 3) reason = endReasonText(s.frames) }
                            LongShotSession.Outcome.CAP -> { ok = true; reason = "Reached the size limit" }
                            else -> c.recycle()
                        }
                    }
                    if (!ok) {
                        val hh = resources.displayMetrics.heightPixels
                        s.logLine("no match after 3 captures: estimated displacement since the last accepted capture ${"%.0f".format(shotSince)}px of ${hh}px, ratio ${"%.2f".format(shotRatio)}, round $mmRound")
                        if (mmRound < 2 && shotSince >= hh * 0.08f) { mmRound++; shotBack = true; s.logLine("recovery: drag back to restore the overlap, round $mmRound") }
                        else if (mmRound < 2) { mmRound++; shotLonger = true; s.logLine("recovery: the page has hardly moved, longer drag, round $mmRound") }
                        else if (s.gaps < 3) {
                            val c = snapBlocking()
                            if (c != null && s.reanchor(c)) { mmRound = 0; shotSince = 0f; shotBack = false; shotLonger = false }
                            else { c?.recycle(); reason = "Stopped: could not line up this page (video or animation on screen?). Saved what was captured" }
                        }
                        else reason = "Stopped: could not line up this page (video or animation on screen?). Saved what was captured"
                    }
                }
                LongShotSession.Outcome.CAP -> reason = "Reached the size limit"
            } }
            firstCycle = false
            if (reason.isNotEmpty()) break
            // ---- 2) scroll up, only when another cycle is wanted
            if (!(shotHolding || shotQueued || shotAuto)) break
            // nothing has moved since the first capture: this is not a proven end. Try again with a longer drag in another column before giving up.
            shotBackMode = false
            if (shotBack) {
                shotBack = false; shotBackMode = true
                val hh = resources.displayMetrics.heightPixels.toFloat()
                val dist = (shotSince * 0.5f / shotRatio).coerceIn(hh * 0.10f, hh * 0.70f)
                shotY0 = (0.5f * hh - dist / 2f) / hh; shotY1 = (0.5f * hh + dist / 2f) / hh; shotX = ScrollService.X0
                afterBack = true; s.logLine("drag back ${"%.2f".format(shotY0)}->${"%.2f".format(shotY1)}")
            } else if (ends > 0 || shotLonger) { shotLonger = false; shotY0 = 0.80f; shotY1 = 0.50f; shotX = floatArrayOf(0.35f, 0.2f, 0.75f, 0.55f)[(ends + mmRound) % 4]; s.logLine("no movement yet: retry drag at x=$shotX") } else shotX = ScrollService.X0
            val sw = swipeBlocking()
            s.logLine("swipe ${"%.2f".format(shotY0)}->${"%.2f".format(shotY1)} ok=$sw")
            if (!sw) { reason = "Scrolling failed. Check that Accessibility is on."; break }
            Thread.sleep(380)
        }
        if (reason.isNotEmpty()) { app.vocr.VocrApp.note("longshot: loop ended: $reason", null); shotEndReason = reason; shotHolding = false; shotAuto = false; ui.post { toast(reason); ui.removeCallbacks(shotIdleRun); finishLong(); ui.postDelayed({ if (shotSession == null && !shotRunning) showAgainBar() }, 1200) } }
    }

    @Volatile private var firstShot: ShotSaver.Result? = null
    private var shotNoteId = 5000
    /** The "ready" notification for a screenshot or long shot, with Share (images, plus the text when Shot text is on). */
    private fun postShotDone(r: ShotSaver.Result, tall: Boolean) = runCatching {
        val imgs = r.images; if (imgs.isEmpty()) return@runCatching
        val title = if (tall) "Long shot saved" else "Screenshot saved"
        val id = shotNoteId++
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(imgs[0], "image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val share = if (imgs.size == 1) Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, imgs[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(imgs))
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val cd = android.content.ClipData.newRawUri(title, imgs[0]); imgs.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) }
        r.textUri?.let { cd.addItem(android.content.ClipData.Item(it)) }
        share.clipData = cd
        if (r.text.isNotBlank() && r.text.length < 100_000) share.putExtra(Intent.EXTRA_TEXT, r.text)
        val chooser = Intent.createChooser(share, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = Notification.Builder(this, CH_DONE).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText((if (imgs.size > 1) "${imgs.size} parts" else "1 image") + " in Download/ScrollRSI. Tap to open, or Share.")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, id, view, PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "Share", PendingIntent.getActivity(this, id + 10000, chooser, PendingIntent.FLAG_IMMUTABLE)).build())
        nm().notify(id, b.build())
    }

    private fun finishLong() {
        val s = shotSession ?: return
        if (shotRunning) { ui.postDelayed(shotIdleRun, 600); return }   // a capture step is still being joined
        val endReason = shotEndReason; shotEndReason = ""
        val logTxt = if (shotLoopRan) runCatching { shotLogText(s, if (endReason.isEmpty() && s.frames <= 1) "Ended with only one image after a scroll attempt" else endReason) }.getOrNull() else null
        val firstStamp = shotStamp
        shotLoopRan = false
        shotSession = null; hideShotBar(); shotHolding = false
        val alreadySaved = shotSaved; shotSaved = false
        shotExec.execute {
            try {
                fun writeLog(st: String) { logTxt?.let { l -> runCatching { app.vocr.core.Saver.saveAs(this, "ScrollRSI shot log $st", l.replaceFirst("ScrollRSI shot log ", "ScrollRSI shot log $st")) } } }
                if (s.frames == 1 && alreadySaved) { writeLog(if (firstStamp.isNotEmpty()) firstStamp else stamp()); s.discard(); firstShot?.let { toast("Saved to Download/ScrollRSI: ${it.names}"); postShotDone(it, false) }; firstShot = null; return@execute }
                val parts = s.finish()
                val tall = s.frames > 1
                val st = stamp()
                val base = (if (tall) "ScrollRSI long shot " else "ScrollRSI shot ") + st
                writeLog(st)
                val n = ShotSaver.save(this, base, parts, prefs(this).getBoolean("shot_text", false))
                toast("Saved to Download/ScrollRSI: ${n.names}" + (if (s.gaps > 0) ". The page could not be lined up ${s.gaps} time(s): a section may be missing between image parts" else "")); postShotDone(n, tall)
                firstShot = null
            } catch (e: Throwable) { toast("Could not save: " + (e.message ?: e.javaClass.simpleName)); runCatching { s.discard() } }
        }
    }


    // ---- screenshot bubble: sits under the record bubble, tucks, wakes and moves with it ---------------------------------
    private var shotBubble: ShotBubble? = null
    private fun shotBubbleWanted() = widget != null && !tucked && !recording && !starting && !readMode && shotSession == null && menu.isEmpty() && !shotRunning
    private fun syncShotBubble() {
        val w = widget; val q = lp
        if (w == null || q == null || !shotBubbleWanted()) { shotBubble?.let { v -> runCatching { wm?.removeView(v) } }; shotBubble = null; return }
        val d = dp(40)
        val x = q.x + (q.width - d) / 2
        val y = q.y + q.height + dp(8)
        val cur = shotBubble
        if (cur == null) {
            val v = ShotBubble(this)
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var wasTucked = false
            v.setOnTouchListener { _, e ->
                val p = lp ?: return@setOnTouchListener true
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                        sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false
                        ui.removeCallbacks(tuckRun); wasTucked = tucked
                        if (tucked) tuck(false)   // first touch wakes both bubbles
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(e.rawX - sx) > dp(6) || Math.abs(e.rawY - sy) > dp(6)) moved = true
                        if (moved) { p.x = ox + (e.rawX - sx).toInt(); p.y = oy + (e.rawY - sy).toInt(); widget?.let { runCatching { wm?.updateViewLayout(it, p) } }; syncShotBubble() }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (moved) { sideRight = p.x + p.width / 2 > screenW() / 2; tuck(false) }
                        else if (!wasTucked && e.action == MotionEvent.ACTION_UP) { startShot(save = true); ui.postDelayed(tuckRun, 3000) }
                        else ui.postDelayed(tuckRun, 3000)
                    }
                }
                true
            }
            val lpp = WindowManager.LayoutParams(d, d, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or secureFlag(), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
            v.alpha = w.alpha
            runCatching { wm?.addView(v, lpp); shotBubble = v }
        } else {
            val cl = cur.layoutParams as WindowManager.LayoutParams
            cl.x = x; cl.y = y; cur.alpha = w.alpha
            runCatching { wm?.updateViewLayout(cur, cl) }
        }
    }
    private val shotPoll = object : Runnable { override fun run() { if (widget == null) return; syncShotBubble(); ui.postDelayed(this, 150) } }

    private fun onWidgetTap() {
        if (readMode) stopReadMode()
        else if (recording) stopRecording()
        else startActivity(Intent(this, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- recording ------------------------------------------------------------------------------------------------
    private fun beginRecording(intent: Intent) {
        starting = true; syncSecure()   // secure before the projection exists, so the first frames never show the bubble
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
            r.setVideoSize(w, h); val light = prefs(this).getBoolean("lightcapture", false)
            r.setVideoFrameRate(if (light) 30 else 60); r.setVideoEncodingBitRate(if (light) 5_000_000 else 10_000_000)
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
                    recording = true; starting = false; paused = false; pauseCount = 0; pauseMs = 0L; app.vocr.Haptics.recStart(this)
                    setWidgetRecording()
                    showFloatBar()   // speed bar + Pause stay on screen during a take too (folds to the edge handle like in Scroll on)
                    if (prefs(this).getBoolean("autoscroll", false) && ScrollService.instance != null) startAutoScroll()
                } catch (e: Throwable) {
                    cover?.remove(); releaseRecording(); recording = false; starting = false; syncSecure(); setWidgetIdle(); fgIdle("Tap the floating button to record")
                    toast("Could not start recording: " + (e.message ?: e.javaClass.simpleName))
                }
            }, if (cv != null) 300L else 0L)
        } catch (e: Throwable) {
            releaseRecording(); recording = false; starting = false; syncSecure(); setWidgetIdle(); fgIdle("Tap the floating button to record")
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
    private val scrollRun = Runnable { try { scrollStep() } catch (e: Exception) { android.util.Log.e("vocr", "scrollStep failed", e); app.vocr.VocrApp.note("scrollStep failed", e); app.vocr.VocrApp.noteStop("error in a scroll step (${e.javaClass.simpleName}), scrolling stopped, recording left running"); stopAutoScroll() } }   // a failed step must stop auto-scroll, never kill the app
    private fun startAutoScroll() { app.vocr.Haptics.autoStart(this); app.vocr.core.Sfx.scroll(this, true); lastThumb = null; still = 0; scrolling = true; syncSecure(); swipes = 0; scrollT0 = System.currentTimeMillis(); ui.postDelayed(scrollRun, 2500) }
    private fun stopAutoScroll() { if (scrolling) app.vocr.Haptics.autoEnd(this); app.vocr.core.Sfx.scroll(this, false); scrolling = false; syncSecure(); ui.removeCallbacks(scrollRun) }
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
    // Pause button (speed bar): freezes the swipes, the bottom check and the time cap; during a take it also pauses the recording itself.
    private var paused = false; private var pauseT0 = 0L; private var pauseCount = 0; private var pauseMs = 0L
    private var pauseBtns: List<TextView> = emptyList()
    private fun canPause() = recording || readMode
    private fun pauseLabel() = if (paused) "Resume" else "Pause"
    private fun refreshPauseUi() {
        pauseBtns = pauseBtns.filter { it.isAttachedToWindow }
        pauseBtns.forEach { b -> b.text = pauseLabel(); b.contentDescription = if (paused) "Resume. Continues scrolling" + (if (recording) " and recording" else "") else "Pause. Stops scrolling" + (if (recording) " and recording" else "") }
        widget?.apply {
            if (paused) { label = "PAUSED"; labelSp = 7f; alpha = 0.85f; fill = Color.parseColor("#3A3B42") }
            else if (recording) { label = "STOP"; labelSp = 9f; alpha = 0.55f; fill = Color.parseColor("#E5484D") }
            else if (readMode) { label = "STOP"; labelSp = 9f; alpha = 0.7f; fill = Color.parseColor("#3A3B42") }
            invalidate()
        }
    }
    private fun togglePause() {
        if (!canPause()) return
        if (!paused) {
            paused = true; pauseT0 = System.currentTimeMillis(); pauseCount++
            if (recording) { val ok = runCatching { recorder?.pause() }.isSuccess; if (!ok) toast("Could not pause the recording; it keeps recording. Scrolling is paused.") }
        } else {
            paused = false; pauseMs += System.currentTimeMillis() - pauseT0
            if (recording) runCatching { recorder?.resume() }
        }
        refreshPauseUi()
    }
    /** Closes an open pause (called when scrolling or the take ends) and resets the per-take counters. */
    private fun endPauseTracking(keepCounts: Boolean) {
        if (paused) { pauseMs += System.currentTimeMillis() - pauseT0; paused = false }
        if (!keepCounts) { pauseCount = 0; pauseMs = 0L }
    }
    private var lastSwipeMs = 740L   // length of the latest swipe cycle (flick + settle at speed 10, the whole drag at slower speeds)
    private fun scrollStep() {
        val svc = ScrollService.instance
        if (!scrolling || !active() || svc == null) return
        val lvl = speedLevel()
        if (lvl == 0 || paused) {   // stopped or paused: wait, and do not let the time cap or the bottom check run
            if (pausedSince == 0L) pausedSince = System.currentTimeMillis()
            ui.postDelayed(scrollRun, 400); return
        }
        if (pausedSince != 0L) { scrollT0 += System.currentTimeMillis() - pausedSince; pausedSince = 0L; still = 0 }
        if (System.currentTimeMillis() - scrollT0 >= capMs()) { endScroll("Auto-scroll done", "time cap"); return }
        val m = resources.displayMetrics
        if (lvl >= 10) {
            lastSwipeMs = 740L
            svc.swipe(m.widthPixels, m.heightPixels, 0.70f, 0.10f, 90, startX()) { ok ->
                if (ok) swipes++
                app.vocr.Haptics.autoPulse(this)
                ui.postDelayed({ checkBottom(svc) }, 650L)   // let the fling settle, then look at the screen
            }
        } else {
            val v = crawlSpeed(lvl)
            val dist = (v * 4.0).coerceIn(0.2, (SLOW_Y0 - 0.06f).toDouble())   // screens per drag; the drag starts at SLOW_Y0 and must end above y=0.06 (a negative end point crashes the gesture builder)
            val dur = (dist / v * 1000).toLong()               // constant speed, no fling at the lift
            lastSwipeMs = dur + 120L
            for (k in 1..(dur / 1500L).toInt()) ui.postDelayed({ if (scrolling && active() && speedLevel() > 0 && !paused) app.vocr.Haptics.autoPulse(this) }, k * 1500L)   // gentle pulse every ~1.5 s during a slow drag
            svc.swipe(m.widthPixels, m.heightPixels, SLOW_Y0, (SLOW_Y0 - dist).toFloat(), dur, startX(SLOW_Y0)) { ok ->
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
    private val SLOW_Y0 = 0.55f   // slow drags start higher: a keyboard that pops up covers roughly the bottom 40%, so keys are never under the finger
    private fun startX(y0: Float = 0.70f): Float {
        val c = floatArrayOf(ScrollService.X0, 0.20f, 0.75f, 0.55f); val i = still.coerceIn(0, 3)
        val m = resources.displayMetrics; val y = (y0 * m.heightPixels).toInt(); val bl = cover?.blocked() ?: emptyList()
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
                if (paused) { ui.postDelayed(scrollRun, 400); return@post }   // a pause can never trigger the bottom stop
                val prev = lastThumb
                if (t != null && prev != null) {
                    var d = 0L; for (i in t.indices) d += Math.abs(t[i] - prev[i])
                    val mad = d.toDouble() / t.size
                    still = if (mad < 1.2) still + 1 else 0
                    // No change after a swipe: the page may just be slow to load. Let go, wait 4 s, try again; each wait is 2 s longer
                    // (4, 6, 8 s). If the third retry still changes nothing, it is the bottom: end the recording.
                    if (still > STUCK_RETRIES) { endScroll(if (readMode) "Reached the bottom" else "Reached the bottom - reading now", "bottom"); return@post }
                    if (still > 0) {
                        // Same confirmation time at every speed (about 21 s from the first still frame): slow drags already take seconds, so the waits shrink by that much.
                        val f = ((21720.0 - 3 * (lastSwipeMs + 500)) / 18000.0).coerceIn(0.1, 1.0)
                        val pause = ((STUCK_FIRST_PAUSE_S + STUCK_STEP_S * (still - 1)) * 1000L * f).toLong()
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
    private fun endScroll(msg: String, why: String = "other") {
        runCatching { app.vocr.VocrApp.noteStop("$why, speed ${speedLevel()}, still=$still, swipes=$swipes, scrolled ${(System.currentTimeMillis() - scrollT0) / 1000}s, ${if (readMode) "Scroll mode" else "take"}") }
        if (readMode) { toast(msg); stopReadMode() }
        else {
            runCatching { stopAutoScroll() }   // whatever happens there, the recording must still be stopped
            toast(msg); stopRecording()
        }
    }

    /** Hands-free reading: auto-scroll with NO recording, no capture and no queue job. Tap the bubble to stop. */
    private fun startReadMode() {
        if (recording) { toast("Stop recording first"); return }
        if (readMode) { toast("Already scrolling - tap the bubble to stop"); return }
        if (ScrollService.instance == null) { toast("Turn on Auto-scroll in ScrollRSI Settings first"); return }
        readMode = true; syncSecure(); pausedSince = 0L
        widget?.apply { label = "STOP"; alpha = 0.7f; labelSp = 9f; fill = Color.parseColor("#3A3B42"); ring = -1f; invalidate() }
        lp?.let { it.width = dp(40); it.height = dp(40); widget?.let { w -> runCatching { wm?.updateViewLayout(w, it) } } }
        tuck(false)
        startAutoScroll()
        showFloatBar()
    }
    private fun stopReadMode() { if (!readMode) return; endPauseTracking(false); readMode = false; hidePace(); hideFloatBar(); stopAutoScroll(); setWidgetIdle(); syncSecure(); ui.post { refreshQueueUi() } }

    private fun stopRecording() {
        if (!recording) return
        runCatching { stopAutoScroll() }
        endPauseTracking(true)
        recording = false; starting = false; syncSecure()
        if (!readMode) hideFloatBar()
        cover?.remove()
        val f = recFile
        app.vocr.VocrApp.setPauseNote(f, pauseCount, pauseMs); pauseCount = 0; pauseMs = 0L
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or secureFlag(), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = lp?.x ?: 0; y = (lp?.y ?: dp(60)) - dp(46) }
        runCatching { wm?.addView(c, q); addChip = c; ui.postDelayed(chipPoll, 300) }
    }

    /** The (+) offers both: queue old recordings, or start a new (auto-scroll) recording while earlier ones are still being read. */
    private fun showAddMenu() {
        val q = lp ?: return
        hideMenu()
        val x = (q.x - dp(176)).coerceAtLeast(dp(4)); val y = (q.y - dp(46)).coerceAtLeast(dp(4))
        val old = chip("Add old recordings", chipC()) {
            hideMenu(); startActivity(Intent(this, AddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val neu = chip(if (recording) "Recording now" else "New recording" + (if (prefs(this).getBoolean("autoscroll", false) && ScrollService.instance != null) " (auto-scroll)" else ""), accentC()) {
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
