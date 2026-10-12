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
        if (Build.VERSION.SDK_INT >= 34) startForeground(JobQueue.ID_QUEUE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(JobQueue.ID_QUEUE, n)
        return START_NOT_STICKY
    }
}
