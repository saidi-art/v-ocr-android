package app.vocr.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import java.io.File
import app.vocr.capture.QueueService
import app.vocr.Haptics
import app.vocr.VocrApp

/**
 * One process-wide queue for everything that needs reading: files picked on the main page and recordings from the widget.
 * Jobs run one at a time (4 OCR threads is the sweet spot on the phone; two jobs at once are slower each), but adding
 * never blocks: a new file or recording just joins the line. Each job has its own scratch folder (see Pipeline).
 */
object JobQueue {
    class Job(val id: Int, val label: String, val uri: Uri?, val tmp: File?) {
        var state by mutableIntStateOf(0)        // 0 queued, 1 running, 2 done, 3 failed, 4 cancelled
        var frac by mutableFloatStateOf(0f)
        var ringLabel by mutableStateOf("")
        var stage by mutableStateOf("Waiting")
        var title by mutableStateOf("")
        var result by mutableStateOf<Uri?>(null)
        var lines by mutableIntStateOf(0)
        var secs by mutableIntStateOf(0)
        @Volatile var cancel = false
    }

    val jobs = mutableStateListOf<Job>()
    /** Called from the worker thread when anything visible changed (progress, state). */
    @Volatile var listener: (() -> Unit)? = null

    private const val CH_STATUS = "vocr_status2"
    private const val CH_DONE = "vocr_done2"
    const val ID_QUEUE = 43
    private val lock = Any()
    private var nextId = 1
    private var running = false

    fun activeCount() = jobs.count { it.state <= 1 }
    fun current(): Job? = jobs.firstOrNull { it.state == 1 }

    fun addUris(ctx: Context, uris: List<Uri>) {
        for (u in uris) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            var name = "recording"
            runCatching {
                ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) name = c.getString(i)
                }
            }
            enqueue(ctx, name, u, null)
        }
    }

    fun addFile(ctx: Context, f: File, label: String) = enqueue(ctx, label, null, f)

    fun cancel(j: Job) { j.cancel = true; synchronized(lock) { if (j.state == 0) { j.state = 4; j.stage = "Cancelled" } else if (j.state == 1) j.stage = "Cancelling..." }; listener?.invoke() }
    fun clearFinished() { synchronized(lock) { jobs.removeAll { it.state >= 2 } } }

    private fun enqueue(ctx: Context, label: String, uri: Uri?, tmp: File?) {
        val app = ctx.applicationContext
        // recordings left behind by a killed process (older than any job that could still be waiting)
        runCatching { val old = System.currentTimeMillis() - 12 * 3600_000L; app.cacheDir.listFiles()?.filter { it.name.startsWith("rec-") && it.lastModified() < old }?.forEach { it.delete() } }
        synchronized(lock) {
            jobs.add(Job(nextId++, label, uri, tmp))
            if (!running) { running = true; Thread { worker(app) }.start() }
        }
        runCatching { app.startForegroundService(Intent(app, QueueService::class.java)) }
        listener?.invoke()
    }

    private const val CUE_LEAD_S = 21
    private const val CUE_MIN_JOB_S = 8

    private fun worker(app: Context) {
        while (true) {
            val job = synchronized(lock) { jobs.firstOrNull { it.state == 0 } ?: run { running = false; null } } ?: break
            runJob(app, job)
        }
        synchronized(lock) { if (!running) app.stopService(Intent(app, QueueService::class.java)) }   // not while a new job just arrived
        listener?.invoke()
    }

    private fun runJob(app: Context, job: Job) {
        job.state = 1; job.stage = "Scanning"; job.ringLabel = "SCAN"; notifyStatus(app); listener?.invoke()
        val wl = (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "vocr:queue").apply { acquire(45 * 60 * 1000L) }
        var lastNote = 0L
        var stripDir: File? = null
        try {
            val src: Uri? = job.uri ?: job.tmp?.let { Uri.fromFile(it) }   // null = built-in demo video
            val res = Pipeline.run(app, src, { pr ->
                job.frac = pr.done / pr.total.coerceAtLeast(1).toFloat()
                listener?.invoke()
            }, { job.cancel })
            stripDir = res.strips.firstOrNull()?.file?.parentFile
            if (job.cancel) { job.state = 4; job.stage = "Cancelled"; return }
            job.stage = "Reading"; job.frac = 0f; job.ringLabel = "0%"; listener?.invoke()
            val fast = app.getSharedPreferences("vocr", Context.MODE_PRIVATE).getBoolean("fast", false)
            val readT0 = System.currentTimeMillis()
            var cueDone = false
            val rd = Reader.readAll(app, res.strips, fast, { job.cancel }) { pr ->
                // End cue: the sound is 22 s long, so start it when the job should have ~21 s left (1 s for stitching and saving).
                // Skipped for jobs whose whole reading takes under 8 s. Started at once when the first estimate is already under 21 s.
                if (!cueDone && !job.cancel && pr.etaS >= 0 && pr.etaS <= CUE_LEAD_S) {
                    cueDone = true
                    val totalS = (System.currentTimeMillis() - readT0) / 1000 + pr.etaS
                    if (totalS >= CUE_MIN_JOB_S) Sfx.cue(app)
                }
                val fr = if (pr.frac >= 0f) pr.frac else pr.done / pr.total.coerceAtLeast(1).toFloat()
                job.frac = fr
                job.ringLabel = (fr * 100).toInt().toString() + "%"
                val eta = if (pr.etaS >= 0) "  ~${pr.etaS / 60}m ${pr.etaS % 60}s left" else ""
                job.stage = "Reading ${pr.done}/${pr.total}$eta"
                val now = System.currentTimeMillis()
                if (now - lastNote > 1500) { lastNote = now; notifyStatus(app) }
                listener?.invoke()
            }
            val ft = RunStats.footer(res, rd).let { b -> VocrApp.takePauseNote(job.tmp)?.let { n -> b + (if (b.endsWith("\n")) "" else "\n") + n } ?: b }
            val sv = Saver.save(app, rd.text.trimEnd() + "\n\n" + ft)
            if (sv.uri == null) throw RuntimeException("could not save the text file to Download/ScrollRSI (" + sv.error + ")")
            job.title = sv.title; job.result = sv.uri; job.lines = rd.mergedLines; job.secs = (rd.ms / 1000).toInt()
            job.stage = "Done"; job.state = 2; job.frac = 1f; Haptics.saved(app)
            postDone(app, job, rd.text)
        } catch (e: JobCancelled) {
            job.state = 4; job.stage = "Cancelled"
        } catch (e: Throwable) {
            job.state = 3; Haptics.failure(app); job.stage = "Failed: " + (e.message ?: e.javaClass.simpleName)
            nm(app).notify(100 + job.id, Notification.Builder(app, CH_DONE).setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("ScrollRSI could not read " + job.label).setContentText(e.message ?: e.javaClass.simpleName).setAutoCancel(true).build())
        } finally {
            Sfx.scan(app, false); if (job.state != 2) Sfx.stopCue(app)
            job.tmp?.delete(); runCatching { stripDir?.deleteRecursively() }; runCatching { if (wl.isHeld) wl.release() }
            listener?.invoke(); notifyStatus(app)
        }
    }

    /** Opening the app counts as seeing the results: drop the "Text ready" notifications. */
    fun clearDoneNotifications(c: Context) {
        runCatching { nm(c).activeNotifications.filter { it.id >= 100 }.forEach { nm(c).cancel(it.id) } }
    }

    private fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun channels(c: Context) {
        // no launcher badge: a count on the app icon that you can't clear is just noise (old channels had it on)
        runCatching { nm(c).deleteNotificationChannel("vocr_status"); nm(c).deleteNotificationChannel("vocr_done") }
        nm(c).createNotificationChannel(NotificationChannel(CH_STATUS, "Recorder status", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm(c).createNotificationChannel(NotificationChannel(CH_DONE, "Text ready", NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) })
    }

    fun statusNote(c: Context): Notification {
        channels(c)
        val cur = current(); val waiting = jobs.count { it.state == 0 }
        val text = if (cur == null) (if (waiting > 0) "$waiting waiting" else "Finishing") else cur.label + " - " + cur.stage + (if (waiting > 0) "  (+$waiting waiting)" else "")
        val b = Notification.Builder(c, CH_STATUS).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("ScrollRSI queue").setContentText(text).setOngoing(true)
        if (cur != null) b.setProgress(100, (cur.frac * 100).toInt().coerceIn(0, 100), false)
        return b.build()
    }

    private fun notifyStatus(c: Context) { if (activeCount() > 0) runCatching { nm(c).notify(ID_QUEUE, statusNote(c)) } }

    private fun postDone(c: Context, job: Job, text: String) {
        channels(c)
        val b = Notification.Builder(c, CH_DONE).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(job.title).setContentText("Text ready: ${job.lines} lines in ${job.secs}s. Tap to open.")
            .setStyle(Notification.BigTextStyle().bigText(text.take(600))).setAutoCancel(true)
        val uri = job.result
        if (uri != null) {
            val view = Intent(c, app.vocr.SavedActivity::class.java).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)   // opens the text inside the app
            b.setContentIntent(PendingIntent.getActivity(c, 1000 + job.id, view, PendingIntent.FLAG_IMMUTABLE))
            val send = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share text")
            b.addAction(Notification.Action.Builder(null, "Share", PendingIntent.getActivity(c, 2000 + job.id, send, PendingIntent.FLAG_IMMUTABLE)).build())
        }
        nm(c).notify(100 + job.id, b.build())
    }
}
