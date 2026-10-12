package app.vocr.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Invisible activity that asks Android for permission to record the screen, then hands the grant to the service. */
class ConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), 77)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_BEGIN)
                .putExtra(CaptureService.EXTRA_CODE, resultCode).putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
        } else {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_DENIED))
        }
        finish()
    }
}
