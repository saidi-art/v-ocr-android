package app.vocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import org.robolectric.RuntimeEnvironment
import app.vocr.capture.Ball
import app.vocr.capture.SphereShade
import app.vocr.sphere.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the Sphere shell's real views to PNGs (build/outputs/sphere-render) so the design tokens can be inspected
 * as pixels, not assumed from code. Day and Night desk: the sphere, the four splash tools, the four popup minis.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SphereRenderTest {
    private fun dp(v: Float) = (v * 2f).toInt()   // xhdpi: the density the views read via resources

    private fun draw(v: View, w: Int, h: Int): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bm))
        return bm
    }

    private fun strip(vararg bms: Bitmap): Bitmap {
        val gap = dp(8f); val pad = dp(10f)
        val w = bms.sumOf { it.width } + gap * (bms.size - 1) + pad * 2
        val h = bms.maxOf { it.height } + pad * 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        var x = pad
        for (b in bms) { c.drawBitmap(b, x.toFloat(), (h - b.height) / 2f, null); x += b.width + gap }
        return out
    }

    @Test
    fun renderShell() {
        val ctx = RuntimeEnvironment.getApplication()
        val dir = File("build/outputs/sphere-render").apply { mkdirs() }
        val day = SphereShade.today()
        for (night in listOf(false, true)) {
            val p = Palette.resolve(if (night) ThemeChoice.NIGHT_DESK else ThemeChoice.DAY, night, day)
            // the sphere (44 dp ball with its glow pad)
            val ball = Ball(ctx, p.sphere, 0.55f, dp(10f).toFloat(), true)
            val sphereBm = draw(ball, dp(64f), dp(64f))
            // the four splash tools at their brief sizes
            fun tool(triplet: IntArray, icon: Int, iconDp: Int, wDp: Int, hDp: Int, label: String, trim: Boolean = false, dot: Boolean = false, glassBg: Int? = null): Bitmap {
                val t = ToolView(ctx, triplet, icon, iconDp, wDp, hDp, if (glassBg != null && night) p.inkOnGlass else p.ink, label)
                if (trim) t.setTrim(p.trim); if (dot) t.setRedDot(true)
                glassBg?.let { t.oval.colors = intArrayOf(it, it, it) }
                return draw(t, dp(64f), dp(56f))
            }
            val toolsBm = strip(
                tool(p.rescue, R.drawable.ic_sph_rescue, Geo.LARGE_ICON_DP, Geo.LARGE_W_DP, Geo.LARGE_H_DP, "Rescue chat", trim = night),
                tool(p.rec, R.drawable.ic_sph_rec, Geo.MED_ICON_DP, Geo.MED_W_DP, Geo.MED_H_DP, "REC", dot = true),
                tool(p.auto, R.drawable.ic_sph_auto, Geo.MED_ICON_DP, Geo.MED_W_DP, Geo.MED_H_DP, "Auto-scroll"),
                tool(p.mini, R.drawable.ic_sph_settings, Geo.SMALL_ICON_DP, Geo.SMALL_W_DP, Geo.SMALL_H_DP, "Settings", trim = night, glassBg = p.settingsBg))
            // the four popup minis, Text lit
            fun mini(icon: Int, label: String, lit: Boolean): Bitmap {
                val t = ToolView(ctx, p.mini, icon, Geo.SMALL_ICON_DP, Geo.SMALL_W_DP, Geo.SMALL_H_DP, p.ink, label)
                t.setLit(lit)
                if (lit) { t.scaleX = 1.12f; t.scaleY = 1.12f }
                return draw(t, dp(44f), dp(44f))
            }
            val minisBm = strip(
                mini(R.drawable.ic_sph_image, "Image", false), mini(R.drawable.ic_sph_text, "Text", true),
                mini(R.drawable.ic_sph_open, "Open", false), mini(R.drawable.ic_sph_share, "Share", false))
            // docked sphere (Day: 50% opacity; Night desk: ember)
            val docked = Ball(ctx, p.sphere, 0.55f, dp(10f).toFloat(), true).apply { if (night) dim = true }
            val dockedBm = draw(docked, dp(64f), dp(64f)).let { b ->
                if (night) b else b.copy(Bitmap.Config.ARGB_8888, true).also { nb ->
                    val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                    for (i in px.indices) px[i] = px[i] and 0x00FFFFFF or (((px[i] ushr 24) / 2) shl 24)
                    nb.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
                }
            }
            val name = if (night) "night-desk" else "day"
            // the sphere carrying the REC dot while recording runs
            val recWrap = FrameLayout(ctx).apply {
                addView(Ball(ctx, p.sphere, 0.55f, dp(10f).toFloat(), true), FrameLayout.LayoutParams(dp(64f), dp(64f), android.view.Gravity.CENTER))
                addView(RecDot(ctx), FrameLayout.LayoutParams(dp(12f), dp(12f), android.view.Gravity.TOP or android.view.Gravity.END).apply { topMargin = dp(10f); marginEnd = dp(10f) })
            }
            val recBm = draw(recWrap, dp(64f), dp(64f))
            File(dir, "$name-rec-dot.png").outputStream().use { recBm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // the charcoal bottom bar, quiet and hot (sphere hovering over it)
            val barQuiet = DropBar(ctx, p); val barHot = DropBar(ctx, p).apply { hot = true }
            val barBm = strip(draw(barQuiet, dp(200f), dp(56f)), draw(barHot, dp(200f), dp(56f)))
            File(dir, "$name-dropbar.png").outputStream().use { barBm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "$name-sphere.png").outputStream().use { sphereBm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "$name-tools.png").outputStream().use { toolsBm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "$name-minis.png").outputStream().use { minisBm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "$name-docked.png").outputStream().use { dockedBm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // sanity: the sphere must actually paint its body colour (rendering really happened)
            val c = sphereBm.getPixel(sphereBm.width / 2, sphereBm.height / 2)
            check(Color.alpha(c) > 200) { "sphere centre not painted (alpha=${Color.alpha(c)}) - rendering did not happen" }
        }
        println("sphere renders written to ${dir.absolutePath}")
    }
}
