package app.vocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import app.vocr.sphere.SetupActivity
import app.vocr.sphere.SphereSettingsActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the two real screens (setup, settings) at phone size so their composition is inspected as pixels too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SphereScreensTest {
    private fun shot(a: android.app.Activity, name: String, dir: File) {
        val w = 720; val h = 1560
        val root = a.findViewById<View>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bm))
        File(dir, name).outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun renderScreens() {
        val dir = File("build/outputs/sphere-render").apply { mkdirs() }
        shot(Robolectric.buildActivity(SetupActivity::class.java).create().get(), "screen-setup.png", dir)
        shot(Robolectric.buildActivity(SphereSettingsActivity::class.java).create().get(), "screen-settings.png", dir)
        println("screens written")
    }
}
