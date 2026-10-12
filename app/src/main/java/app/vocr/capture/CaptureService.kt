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
        private const val CH_STATUS = "vocr_status"
        private const val CH_DONE = "vocr_done"
        private const val ID_FG = 41
        private const val ID_DONE = 42
        fun prefs(ctx: Context) = ctx.getSharedPreferences("vocr", Context.MODE_PRIVATE)
    }

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var widget: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var recFile: File? = null
    private var recording = false
    private var busy = false

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

    override fun onDestroy() { removeWidget(); releaseRecording(); super.onDestroy() }

    // ---- notifications --------------------------------------------------------------------------------------------
    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun channels() {
        nm().createNotificationChannel(NotificationChannel(CH_STATUS, "Recorder status", NotificationManager.IMPORTANCE_LOW))
        nm().createNotificationChannel(NotificationChannel(CH_DONE, "Text ready", NotificationManager.IMPORTANCE_HIGH))
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
        return if (sideRight) { if (tuckedNow) screenW() - dp(14) else screenW() - w - dp(6) } else { if (tuckedNow) -(w - dp(14)) else dp(6) }
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
        tucked = on
        widget?.alpha = if (on) 0.6f else if (recording) 0.55f else 1f
        slideTo(targetX(on))
        if (!on) ui.postDelayed(tuckRun, 3000)
    }

    private fun addWidget() {
        if (widget != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            text = "REC"; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#7C5CFF")) }
        }
        val p = WindowManager.LayoutParams(dp(52), dp(52), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = screenW() - dp(14); y = dp(220)
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var longDone = false; var wasTucked = false
        val longRun = Runnable {
            longDone = true
            if (recording || busy) toast("Stop recording first") else closeWidget()
        }
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false; longDone = false
                    ui.removeCallbacks(tuckRun)
                    wasTucked = tucked
                    if (tucked) tuck(false)            // first touch on the sliver slides the bubble out
                    else ui.postDelayed(longRun, 700)  // long press closes it
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

    private fun closeWidget() { removeWidget(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    private fun removeWidget() { ui.removeCallbacks(tuckRun); widget?.let { runCatching { wm?.removeView(it) } }; widget = null }

    private fun setWidgetIdle() {
        widget?.apply { text = "REC"; alpha = 1f; textSize = 11f; (background as GradientDrawable).setColor(Color.parseColor("#7C5CFF")) }
        lp?.let { it.width = dp(52); it.height = dp(52); widget?.let { w -> runCatching { wm?.updateViewLayout(w, it) } } }
        tuck(false)
    }

    private fun setWidgetRecording() {
        widget?.apply { text = "STOP"; alpha = 0.55f; textSize = 9f; (background as GradientDrawable).setColor(Color.parseColor("#E5484D")) }
        lp?.let { it.width = dp(40); it.height = dp(40); widget?.let { w -> runCatching { wm?.updateViewLayout(w, it) } } }
        tuck(false)
    }

    private fun onWidgetTap() {
        if (busy) { toast("Still reading the last recording"); return }
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
            val mp = mgr.getMediaProjection(code, data)
            mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() { ui.post { if (recording) stopRecording() } } }, ui)
            val m = resources.displayMetrics
            var w = m.widthPixels; var h = m.heightPixels
            val maxW = 1080; if (w > maxW) { h = h * maxW / w; w = maxW }
            w = w / 2 * 2; h = h / 2 * 2
            val f = File(cacheDir, "rec.mp4").also { it.delete() }
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

    private fun stopRecording() {
        if (!recording) return
        recording = false
        val f = recFile
        val ok = runCatching { recorder?.stop() }.isSuccess
        releaseRecording()
        setWidgetIdle()
        if (!ok || f == null || !f.exists() || f.length() < 1000) { fgIdle("Tap the floating button to record"); toast("Recording was too short"); return }
        busy = true
        nm().notify(ID_FG, statusNote("Reading the recording..."))
        val fast = prefs(this).getBoolean("fast", false)
        Thread {
            try {
                val res = Pipeline.run(applicationContext, Uri.fromFile(f), { })
                var lastNote = 0L
                val rd = Reader.readAll(applicationContext, res.strips, fast) { pr ->
                    val now = System.currentTimeMillis()
                    if (now - lastNote > 1500) {
                        lastNote = now
                        val eta = if (pr.etaS >= 0) "  ~${pr.etaS / 60}m ${pr.etaS % 60}s left" else ""
                        nm().notify(ID_FG, statusNote("Reading ${pr.done}/${pr.total}$eta", progress = Pair(pr.done, pr.total.coerceAtLeast(1))))
                    }
                }
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US).format(java.util.Date())
                val title = Title.from(rd.text, "V-OCR $stamp")
                val uri = saveText(title, rd.text)
                postDone(title, rd.text, uri, rd.mergedLines, rd.ms)
            } catch (e: Throwable) {
                nm().notify(ID_DONE, Notification.Builder(this, CH_DONE).setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("V-OCR could not read that recording").setContentText(e.message ?: e.javaClass.simpleName).setAutoCancel(true).build())
            } finally {
                f.delete(); busy = false
                ui.post { fgIdle("Tap the floating button to record") }
            }
        }.start()
    }

    // ---- result ---------------------------------------------------------------------------------------------------
    private fun saveText(title: String, text: String): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/V-OCR")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return uri
    }

    private fun postDone(title: String, text: String, uri: Uri?, lines: Int, ms: Long) {
        channels()
        val b = Notification.Builder(this, CH_DONE).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText("Text ready: $lines lines in ${ms / 1000}s. Tap to open.")
            .setStyle(Notification.BigTextStyle().bigText(text.take(600))).setAutoCancel(true)
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            b.setContentIntent(PendingIntent.getActivity(this, 1, view, PendingIntent.FLAG_IMMUTABLE))
            val send = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share text")
            b.addAction(Notification.Action.Builder(null, "Share", PendingIntent.getActivity(this, 2, send, PendingIntent.FLAG_IMMUTABLE)).build())
        }
        nm().notify(ID_DONE, b.build())
    }
}
