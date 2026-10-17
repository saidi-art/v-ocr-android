package app.vocr.capture

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.vocr.core.JobQueue

/** Keeps the process alive and shows the queue notification while jobs are being read. Stopped by the queue when it empties. */
class QueueService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = JobQueue.statusNote(this)
        // Android 15 and later: the media processing type (reading a video on the device). Android 14: that type does not exist, so special use is the only fit.
        if (Build.VERSION.SDK_INT >= 35) startForeground(JobQueue.ID_QUEUE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (Build.VERSION.SDK_INT >= 34) startForeground(JobQueue.ID_QUEUE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(JobQueue.ID_QUEUE, n)
        return START_NOT_STICKY
    }

    /** Android 15 stops a media processing service after about 6 hours in 24: leave the foreground state at once (the system gives a few seconds). Jobs queued later start the service again. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        app.vocr.VocrApp.note("queue service timed out (type $fgsType): stopping the foreground state", null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
