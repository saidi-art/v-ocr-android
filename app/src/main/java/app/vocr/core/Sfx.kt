package app.vocr.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import app.vocr.R

/** Three stage sounds. Auto-scroll loop > scan loop (only one loop at a time); the done sound plays once and holds the loops back. */
object Sfx {
    private var ctx: Context? = null
    private var scrollOn = false
    private var scanOn = false
    private var doneOn = false
    private var scrollP: MediaPlayer? = null
    private var scanP: MediaPlayer? = null
    private var doneP: MediaPlayer? = null

    /** Playback levels (0..1 of the media volume). Tuned low on request; flag for S24 tuning. */
    private const val VOL_SCROLL = 0.30f
    private const val VOL_DONE = 0.35f
    private const val VOL_SCAN = 0.25f

    private fun make(c: Context, res: Int, loop: Boolean): MediaPlayer? = runCatching {
        MediaPlayer.create(c, res, android.media.AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(), 0)?.apply { isLooping = loop; val v = when (res) { R.raw.sfx_done -> VOL_DONE; R.raw.sfx_scan -> VOL_SCAN; else -> VOL_SCROLL }; setVolume(v, v) }
    }.getOrNull()

    @Synchronized private fun update() {
        val c = ctx ?: return
        val snd = c.getSharedPreferences("vocr", Context.MODE_PRIVATE).getBoolean("sound", true)
        if (!snd) { doneP?.let { runCatching { it.release() } }; doneP = null; doneOn = false }
        val wantScroll = snd && scrollOn && !doneOn
        val wantScan = snd && scanOn && !scrollOn && !doneOn
        if (wantScroll) { if (scrollP == null) scrollP = make(c, R.raw.sfx_scroll, true); runCatching { scrollP?.let { if (!it.isPlaying) it.start() } } }
        else scrollP?.let { runCatching { it.release() }; scrollP = null }
        if (wantScan) { if (scanP == null) scanP = make(c, R.raw.sfx_scan, true); runCatching { scanP?.let { if (!it.isPlaying) it.start() } } }
        else scanP?.let { runCatching { it.release() }; scanP = null }
    }

    /** Called when the Sound switch changes: stops everything when turned off. */
    fun refresh(c: Context) { ctx = c.applicationContext; update() }
    fun scroll(c: Context, on: Boolean) { ctx = c.applicationContext; scrollOn = on; update() }
    fun scan(c: Context, on: Boolean) { ctx = c.applicationContext; scanOn = on; update() }

    /** The 22 s end cue. Started by the queue when the job is estimated to finish in about that long; plays out in full. */
    @Synchronized fun cue(c: Context) {
        if (doneP != null) return
        ctx = c.applicationContext
        if (!c.getSharedPreferences("vocr", Context.MODE_PRIVATE).getBoolean("sound", true)) return
        doneOn = true; update()
        doneP = make(c.applicationContext, R.raw.sfx_done, false)?.also { p ->
            p.setOnCompletionListener { synchronized(this) { runCatching { it.release() }; if (doneP === it) doneP = null; doneOn = false; update() } }
            runCatching { p.start() }
        } ?: run { doneOn = false; null }
        update()
    }

    /** Cancelled or failed job: cut the cue. */
    @Synchronized fun stopCue(c: Context) {
        ctx = c.applicationContext
        doneP?.let { runCatching { it.release() } }; doneP = null
        doneOn = false; update()
    }
}
