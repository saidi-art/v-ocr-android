package app.vocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import app.vocr.capture.CaptureService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the engine's real Speed pill (shown only while Auto-scroll or REC runs) in both themes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SphereSpeedTest {
    @Test
    fun renderSpeedPill() {
        val dir = File("build/outputs/sphere-render").apply { mkdirs() }
        for (theme in listOf("day", "night")) {
            val svc = Robolectric.buildService(CaptureService::class.java).get()
            svc.getSharedPreferences("sphere", Context.MODE_PRIVATE).edit().putString("theme", theme).commit()
            val pill = svc.buildSpeedPill()
            val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            pill.measure(spec, spec)
            val w = pill.measuredWidth; val h = pill.measuredHeight
            check(w > 0 && h > 0) { "speed pill measured $w x $h" }
            pill.layout(0, 0, w, h)
            val bm = Bitmap.createBitmap(w + 40, h + 40, Bitmap.Config.ARGB_8888)
            val c = Canvas(bm); c.translate(20f, 20f)
            pill.draw(c)
            File(dir, "speed-$theme.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        println("speed renders written")
    }
}
