package app.vocr.sphere

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import app.vocr.capture.Ball
import app.vocr.capture.CaptureService
import app.vocr.capture.SphereShade

/**
 * The sphere is the whole interface (brief). It stays on screen across every app and rides along on each switch:
 * capture here, switch, drop there. Tap = quick capture. Long press (377 ms) = tool splash. Drag = move, tools
 * follow elastically, release snaps to the nearest edge and remembers the position. Idle 5 s = dock 40% off the edge.
 * After a capture, the four minis pop out; the capture also rests in the shade as the fallback.
 *
 * Capture work itself stays in the engine (CaptureService): this service owns only the overlay and the hand-off.
 */
class SphereService : Service(), SphereBridge.Listener {
    companion object {
        const val ACTION_SHOW = "app.vocr.sphere.SHOW"
        const val ACTION_HIDE = "app.vocr.sphere.HIDE"
        const val ACTION_THEME = "app.vocr.sphere.THEME"
        private const val CH = "sphere_status"
        private const val ID_FG = 71
        fun prefs(ctx: Context) = ctx.getSharedPreferences("sphere", Context.MODE_PRIVATE)
        fun isOn(ctx: Context) = prefs(ctx).getBoolean("on", false)
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var container: FrameLayout? = null
    private var ball: Ball? = null
    private var lp: WindowManager.LayoutParams? = null
    private var palette: Palette = Palette.day(SphereShade.today())
    private lateinit var splash: Splash
    private lateinit var popup: Popup
    private var dropBar: DropBar? = null
    private var dropLp: WindowManager.LayoutParams? = null
    private var recDot: RecDot? = null
    private var recActive = false
    private var docked = false
    private var dragging = false
    private var longFired = false
    private var snapAnim: SpringAnimation? = null
    private val dockRun = Runnable { dock(true) }
    private val longRun = Runnable {
        longFired = true
        val v = container ?: return@Runnable
        v.performHapticFeedback(Haptic.SPLASH)
        openSplash()
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        splash = Splash(this, wm, ui)
        popup = Popup(this, wm, ui)
        nm().createNotificationChannel(NotificationChannel(CH, "Sphere", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { hide(); return START_NOT_STICKY }
            ACTION_THEME -> {
                // the theme choice changed in settings: re-resolve the palette and rebuild in place
                resolvePalette()
                if (container != null) retheme() else stopSelf()
            }
            else -> show()
        }
        return START_STICKY
    }

    override fun onDestroy() { SphereBridge.listener = null; splash.close(false); popup.close(false); removeSphere(); super.onDestroy() }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        resolvePalette()
        ui.post { val q = lp ?: return@post; clampY(q); if (!docked) snap(q, instant = true) else dock(true); splash.close(false); popup.close(false); hideDropBar() }
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------------

    private fun show() {
        prefs(this).edit().putBoolean("on", true).apply()
        startForeground(ID_FG, statusNote(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        SphereBridge.listener = this
        if (container != null) return
        resolvePalette()
        addSphere()
    }

    private fun hide() {
        prefs(this).edit().putBoolean("on", false).apply()
        SphereBridge.listener = null
        splash.close(false); popup.close(false); removeSphere()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun removeSphere() {
        hideDropBar()
        ui.removeCallbacks(dockRun); ui.removeCallbacks(longRun)
        snapAnim?.cancel(); snapAnim = null
        container?.let { runCatching { wm.removeView(it) } }; container = null; ball = null; lp = null
    }

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private fun statusNote(): Notification =
        Notification.Builder(this, CH).setSmallIcon(app.vocr.R.drawable.ic_stat_chevron)
            .setContentTitle("Sphere").setContentText("On screen. Tap to capture; hold for tools.").setOngoing(true).build()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    private fun resolvePalette() { palette = Palette.current(this) }

    // ---- the sphere -----------------------------------------------------------------------------------------------

    private fun sphereRect(): Rect {
        val q = lp ?: return Rect()
        val v = container ?: return Rect()
        return Rect(q.x, q.y, q.x + v.width, q.y + v.height)
    }

    private fun addSphere() {
        val d = resources.displayMetrics.density
        val size = dp(64)   // the 44 dp sphere + glow room inside a generous touch wrapper (48 dp minimum)
        val b = Ball(this, palette.sphere, 0.55f, 10f * d, true)
        val c = FrameLayout(this).apply {
            // the Ball view is the 44 dp sphere PLUS its 10 dp halo pad: 64 dp, so the sphere itself is the brief's 44 dp
            addView(b, FrameLayout.LayoutParams(dp(Geo.SPHERE_DP + 20), dp(Geo.SPHERE_DP + 20), Gravity.CENTER))
            contentDescription = "Sphere. Tap to capture; hold for tools; drag to move."
        }
        val q = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        q.gravity = Gravity.TOP or Gravity.START
        val p = prefs(this)
        q.x = if (p.getBoolean("edge_right", true)) screenW() - size else 0
        q.y = p.getInt("y", screenH() / 3)
        attachGestures(c)
        runCatching { wm.addView(c, q) }
        container = c; ball = b; lp = q
        if (!app.vocr.VocrApp.reducedMotion() && !palette.night) b.startPulse()   // Day: breathing pulse; Night desk flicker is a polish value
        if (app.vocr.VocrApp.reducedMotion()) b.stopPulse()
        ui.removeCallbacks(dockRun); ui.postDelayed(dockRun, Motions.DOCK_AFTER)
    }

    private fun attachGestures(c: View) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        c.setOnTouchListener { v, e ->
            val q = lp ?: return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wake()
                    downX = e.rawX; downY = e.rawY; startX = q.x; startY = q.y
                    dragging = false; longFired = false
                    ui.postDelayed(longRun, Motions.LONG_PRESS)   // long press fires at 377 ms (brief)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        dragging = true
                        ui.removeCallbacks(longRun)
                        popup.pauseVanish()   // the vanish timer pauses while the sphere is dragged (brief)
                        showDropBar()
                    }
                    if (dragging) {
                        dropBar?.hot = e.rawY > screenH() - dp(72)
                        // the sphere follows the finger 1:1; open tools chase it with Trail
                        q.x = (startX + dx).toInt().coerceIn(-dp(40), screenW() - dp(24))
                        q.y = (startY + dy).toInt(); clampY(q)
                        runCatching { wm.updateViewLayout(v, q) }
                        splash.follow(sphereRect()); popup.follow(sphereRect())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longRun)
                    if (e.actionMasked == MotionEvent.ACTION_UP && !dragging && !longFired) {
                        if (popup.isShowing()) { popup.touch() } else quickCapture()
                    } else if (dragging) {
                        val drop = dropBar?.hot == true
                        hideDropBar()
                        if (drop) {
                            // dropped on the charcoal bar: auto capture - a measured part of the conversation, ended with Stop
                            v.performHapticFeedback(Haptic.CAPTURE)
                            engine(CaptureService.ACTION_AUTO_CAPTURE)
                        }
                        // release: snap to the nearest edge with Settle, remember the position, tools re-fan towards the centre
                        snap(q) {
                            prefs(this).edit().putBoolean("edge_right", q.x > screenW() / 2 - dp(32)).putInt("y", q.y).apply()
                            splash.close(); popup.close()
                        }
                    } else hideDropBar()
                    dragging = false
                    armDock()
                    true
                }
                else -> true
            }
        }
    }

    private fun clampY(q: WindowManager.LayoutParams) { q.y = q.y.coerceIn(0, (screenH() - dp(64)).coerceAtLeast(0)) }

    private fun snap(q: WindowManager.LayoutParams, instant: Boolean = false, after: (() -> Unit) = {}) {
        snapAnim?.cancel()
        val target = if (q.x + dp(32) > screenW() / 2) screenW() - dp(64) else 0
        if (instant || app.vocr.VocrApp.reducedMotion()) { q.x = target; runCatching { wm.updateViewLayout(container ?: return, q) }; after(); return }
        val from = q.x.toFloat()
        snapAnim = SpringAnimation(FloatValueHolder(0f))
            .setSpring(SpringForce(Springs.SETTLE_STIFF).apply { dampingRatio = Springs.SETTLE_DAMP })
            .addUpdateListener { _, value, _ -> q.x = (from + (target - from) * value / 100f).toInt(); runCatching { wm.updateViewLayout(container ?: return@addUpdateListener, q) } }
            .apply { addEndListener { _, _, _, _ -> after() }; animateToFinalPosition(100f) }
    }

    // ---- docking --------------------------------------------------------------------------------------------------

    private fun armDock() { ui.removeCallbacks(dockRun); ui.postDelayed(dockRun, Motions.DOCK_AFTER) }

    private fun wake() {
        ui.removeCallbacks(dockRun)
        if (docked) {
            docked = false
            val q = lp ?: return
            val size = dp(64)
            q.x = if (q.x + size / 2 > screenW() / 2) screenW() - size else 0
            runCatching { wm.updateViewLayout(container ?: return, q) }
            container?.alpha = 1f; ball?.dim = false
        }
    }

    /** After 5 s idle the sphere slides 40% off its edge (brief). Day: 50% opacity. Night desk: dims to an ember. */
    private fun dock(on: Boolean) {
        val q = lp ?: return; val v = container ?: return
        if (splash.isOpen() || popup.isShowing() || dragging) { armDock(); return }
        docked = on
        val size = dp(64)
        val right = q.x + size / 2 > screenW() / 2
        val target = if (on) (if (right) screenW() - (size * (1f - Motions.DOCK_FRACTION)).toInt() else -(size * Motions.DOCK_FRACTION).toInt())
                     else (if (right) screenW() - size else 0)
        if (app.vocr.VocrApp.reducedMotion()) { q.x = target; runCatching { wm.updateViewLayout(v, q) } }
        else {
            val from = q.x.toFloat()
            SpringAnimation(FloatValueHolder(0f))
                .setSpring(SpringForce(Springs.SETTLE_STIFF).apply { dampingRatio = Springs.SETTLE_DAMP })
                .addUpdateListener { _, value, _ -> q.x = (from + (target - from) * value / 100f).toInt(); runCatching { wm.updateViewLayout(v, q) } }
                .animateToFinalPosition(100f)
        }
        v.alpha = if (on && !palette.night) 0.5f else 1f
        ball?.dim = on && palette.night   // the ember
    }

    // ---- actions --------------------------------------------------------------------------------------------------

    private fun engine(action: String) {
        runCatching {
            val i = Intent(this, CaptureService::class.java).setAction(action)
            startForegroundService(i)
        }.onFailure { app.vocr.VocrApp.note("sphere: engine start failed for $action", it) }
    }

    /** Tap: quick capture - one or a few messages (brief). The popup appears when the capture is ready. */
    private fun quickCapture() {
        container?.performHapticFeedback(Haptic.CAPTURE)
        engine(CaptureService.ACTION_QUICK)
    }

    private fun openSplash() {
        splash.open(palette, sphereRect(), object : Splash.Actions {
            override fun onTool(t: Splash.Tool) {
                when (t) {
                    Splash.Tool.RESCUE -> engine(CaptureService.ACTION_RESCUE)
                    Splash.Tool.REC -> engine(CaptureService.ACTION_REC)
                    Splash.Tool.AUTO -> engine(CaptureService.ACTION_AUTOSCROLL)
                    Splash.Tool.SETTINGS -> startActivity(Intent(this@SphereService, SphereSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        })
    }

    /** Rebuild the sphere with the freshly resolved palette, keeping its position, dock state and REC dot. */
    private fun retheme() {
        val q = lp ?: return
        val wasX = q.x; val wasY = q.y; val wasDocked = docked; val recOn = recActive
        splash.close(false); popup.close(false)
        removeSphere()
        addSphere()
        lp?.let { it.x = wasX; it.y = wasY; runCatching { wm.updateViewLayout(container ?: return@let, it) } }
        if (wasDocked) dock(true)
        if (recOn) { recActive = false; onRecState(true) }
    }

    // ---- the charcoal bottom bar (auto capture) ---------------------------------------------------------------------

    private fun showDropBar() {
        if (dropBar != null) return
        val bar = DropBar(this, palette)
        val q = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, dp(56), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        q.gravity = Gravity.BOTTOM or Gravity.START
        runCatching { wm.addView(bar, q) }.onSuccess {
            dropBar = bar; dropLp = q
            if (!app.vocr.VocrApp.reducedMotion()) {
                bar.translationY = dp(56).toFloat()
                bar.animate().translationY(0f).setDuration(Motions.F233).start()
            }
        }
    }

    private fun hideDropBar() {
        dropBar?.let { runCatching { wm.removeView(it) } }; dropBar = null; dropLp = null
    }

    // ---- REC state: the sphere carries the red dot while recording runs ----------------------------------------------

    override fun onRecState(active: Boolean) {
        ui.post {
            if (active == recActive) return@post
            recActive = active
            val c = container ?: return@post
            if (active) {
                val dot = RecDot(this)
                // same dot the REC tool carries (brief): 6 dp, seated on the ball's top-right edge
                c.addView(dot, FrameLayout.LayoutParams(dp(6), dp(6), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(13); marginEnd = dp(13)
                })
                recDot = dot
                c.contentDescription = "Sphere. Recording. Tap to capture; hold for tools; drag to move."
            } else {
                recDot?.let { c.removeView(it) }; recDot = null
                c.contentDescription = "Sphere. Tap to capture; hold for tools; drag to move."
            }
        }
    }

    // ---- the after-capture popup (SphereBridge.Listener) -----------------------------------------------------------

    override fun onCaptureReady(m: CaptureStore.Meta) {
        ui.post {
            wake(); armDock()
            container?.performHapticFeedback(Haptic.READY)
            CaptureStore.logStep(this, m.baseName(), "capture-ready", "type=${m.type.wire} text=${m.textSource.wire}")
            popup.show(palette, m, sphereRect(), object : Popup.Actions {
                override fun onOpen(m: CaptureStore.Meta, format: CaptureStore.Format) = openCapture(m, format)
                override fun onShare(m: CaptureStore.Meta, format: CaptureStore.Format) = shareCapture(m, format)
            })
        }
    }

    override fun onCaptureFailed(kind: CaptureStore.CaptureType, reason: String) {
        ui.post { CaptureStore.logStep(this, "-", "capture-failed", "type=${kind.wire}: $reason") }
    }

    private fun uriFor(m: CaptureStore.Meta, format: CaptureStore.Format) =
        if (format == CaptureStore.Format.TEXT) m.textUri else m.imageUris.firstOrNull()

    private fun openCapture(m: CaptureStore.Meta, format: CaptureStore.Format) {
        val u = uriFor(m, format) ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, if (format == CaptureStore.Format.TEXT) "text/plain" else "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { app.vocr.VocrApp.note("sphere: open failed", it) }
    }

    /** Share hands the capture to the next tool. v1: the system share sheet; remembered destinations are an open brief decision. */
    private fun shareCapture(m: CaptureStore.Meta, format: CaptureStore.Format) {
        val uris = if (format == CaptureStore.Format.TEXT) listOfNotNull(m.textUri) else m.imageUris
        if (uris.isEmpty()) return
        runCatching {
            val share = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(if (format == CaptureStore.Format.TEXT) "text/plain" else "image/png").putExtra(Intent.EXTRA_STREAM, uris[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            share.clipData = android.content.ClipData.newRawUri("Sphere capture", uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
            startActivity(Intent.createChooser(share, "Hand to").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { app.vocr.VocrApp.note("sphere: share failed", it) }
    }
}
