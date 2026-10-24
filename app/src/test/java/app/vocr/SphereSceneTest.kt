package app.vocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.vocr.capture.Ball
import app.vocr.capture.SphereShade
import app.vocr.sphere.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The assembled overlay at realistic phone scale (360 x 780 dp at 2x), composed from the same view classes the
 * services use, on light / dark / checker backgrounds: sphere (+ REC dot), the tool splash fanned to the side,
 * the after-capture popup, and the charcoal drop bar. Geometry mirrors Splash.params / Popup.params exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SphereSceneTest {
    private val D = 2f                      // render density: 2x
    private fun dp(v: Int) = (v * D).toInt()
    private val W get() = dp(360); private val H get() = dp(780)

    private fun place(parent: FrameLayout, v: View, w: Int, h: Int, x: Int, y: Int) {
        parent.addView(v, FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START).apply { leftMargin = x; topMargin = y })
    }

    private fun sphereWin(p: Palette, rec: Boolean): FrameLayout {
        val ctx = RuntimeEnvironment.getApplication()
        return FrameLayout(ctx).apply {
            addView(Ball(ctx, p.sphere, 0.55f, 10f * D, true).apply { if (p.night) { trimColor = p.trim; poolColor = p.glow } }, FrameLayout.LayoutParams(dp(Geo.SPHERE_DP + 20), dp(Geo.SPHERE_DP + 20), Gravity.CENTER))
            if (rec) addView(RecDot(ctx), FrameLayout.LayoutParams(dp(6), dp(6), Gravity.TOP or Gravity.END).apply { topMargin = dp(13); marginEnd = dp(13) })
        }
    }

    private fun splashCol(p: Palette): LinearLayout {
        val ctx = RuntimeEnvironment.getApplication()
        fun tool(triplet: IntArray, icon: Int, iconDp: Int, wDp: Int, hDp: Int, label: String, rescue: Boolean = false, rec: Boolean = false, settings: Boolean = false): ToolView {
            val ink = if (p.night && (rescue || settings)) p.inkOnGlass else p.ink
            return ToolView(ctx, triplet, icon, iconDp, wDp, hDp, ink, label).apply {
                if (rec) setRedDot(true)
                if (p.night && rescue) setTrim(p.trim)
                if (settings) { oval.colors = intArrayOf(p.settingsBg, p.settingsBg, p.settingsBg); if (p.night) setTrim(p.trim) }
                val pad = ((Geo.MIN_TOUCH_DP - hDp).coerceAtLeast(0) * D / 2).toInt()
                setPadding(pad, pad, pad, pad)
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(tool(p.rescue, R.drawable.ic_sph_rescue, Geo.LARGE_ICON_DP, Geo.LARGE_W_DP, Geo.LARGE_H_DP, "Rescue chat", rescue = true))
            addView(tool(p.rec, R.drawable.ic_sph_rec, Geo.MED_ICON_DP, Geo.MED_W_DP, Geo.MED_H_DP, "REC: record what you scroll", rec = true))
            addView(tool(p.auto, R.drawable.ic_sph_auto, Geo.MED_ICON_DP, Geo.MED_W_DP, Geo.MED_H_DP, "Auto-scroll"))
            addView(tool(p.mini, R.drawable.ic_sph_settings, Geo.SMALL_ICON_DP, Geo.SMALL_W_DP, Geo.SMALL_H_DP, "Settings", settings = true))
        }
    }

    private fun popupRow(p: Palette, hint: Boolean): LinearLayout {
        val ctx = RuntimeEnvironment.getApplication()
        fun mini(icon: Int, label: String, lit: Boolean): ToolView {
            val t = ToolView(ctx, p.mini, icon, Geo.SMALL_ICON_DP, Geo.SMALL_W_DP, Geo.SMALL_H_DP, p.ink, label)
            val pad = ((Geo.MIN_TOUCH_DP - Geo.SMALL_H_DP) * D / 2).toInt()
            t.setPadding(pad, pad, pad, pad)
            if (lit) { t.setLit(true); t.scaleX = 1.12f; t.scaleY = 1.12f }
            return t
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(mini(R.drawable.ic_sph_image, "Image: save as image", false),
                   mini(R.drawable.ic_sph_text, "Text: save as text", true),
                   mini(R.drawable.ic_sph_open, "Open: review this capture", false),
                   mini(R.drawable.ic_sph_share, "Share: hand this capture to another app", false)).forEach { v ->
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    addView(v)
                    if (hint) addView(TextView(ctx).apply {
                        text = v.label.substringBefore(':'); textSize = 10f
                        setTextColor(if (p.night) 0xFFF3E7CB.toInt() else 0xFF191919.toInt())
                        setShadowLayer(2f, 0f, 1f, 0x80000000.toInt())
                    })
                })
            }
        }
    }

    private fun background(kind: String): View {
        val ctx = RuntimeEnvironment.getApplication()
        return when (kind) {
            "light" -> View(ctx).apply { setBackgroundColor(0xFFFAF9F6.toInt()) }   // a light chat background
            "dark" -> View(ctx).apply { setBackgroundColor(0xFF17181C.toInt()) }    // a dark chat background
            else -> object : View(ctx) {                                            // checker: proves true transparency
                private val pnt = Paint()
                override fun onDraw(c: Canvas) {
                    val s = dp(8)
                    for (y in 0 until height / s + 1) for (x in 0 until width / s + 1) {
                        pnt.color = if ((x + y) % 2 == 0) 0xFFCFCFCF.toInt() else 0xFF9A9A9A.toInt()
                        c.drawRect((x * s).toFloat(), (y * s).toFloat(), ((x + 1) * s).toFloat(), ((y + 1) * s).toFloat(), pnt)
                    }
                }
            }
        }
    }

    private fun draw(root: View): Bitmap {
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, W, H)
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bm))
        return bm
    }

    @Test
    fun renderScenes() {
        val ctx = RuntimeEnvironment.getApplication()
        val dir = File("build/outputs/sphere-render").apply { mkdirs() }
        val day = SphereShade.today()
        val sphereX = W - dp(64); val sphereY = dp(300)          // sphere window, right edge, mid screen
        val sphereRect = android.graphics.Rect(sphereX, sphereY, sphereX + dp(64), sphereY + dp(64))
        for (night in listOf(false, true)) {
            val p = Palette.resolve(if (night) ThemeChoice.NIGHT_DESK else ThemeChoice.DAY, night, day)
            val theme = if (night) "night" else "day"
            for (bg in listOf("light", "dark", "checker")) {
                // Scene A: sphere (REC running) + tool splash + drop bar
                run {
                    val screen = FrameLayout(ctx)
                    place(screen, background(bg), W, H, 0, 0)
                    val splash = splashCol(p)
                    splash.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    // touch targets: every tool measures at least the 44 dp minimum in both dimensions (brief)
                    for (i in 0 until splash.childCount) {
                        val t = splash.getChildAt(i)
                        check(t.measuredWidth >= dp(Geo.MIN_TOUCH_DP) && t.measuredHeight >= dp(Geo.MIN_TOUCH_DP)) { "tool $i below the 44 dp touch minimum: ${t.measuredWidth}x${t.measuredHeight}" }
                    }
                    // visible geometry: the ovals are exactly the brief's sizes at this density (Rescue 50x40 dp)
                    val rescueLp = (splash.getChildAt(0) as ToolView).oval.layoutParams
                    check(rescueLp.width == dp(Geo.LARGE_W_DP) && rescueLp.height == dp(Geo.LARGE_H_DP)) { "rescue oval ${rescueLp.width}x${rescueLp.height}px, expected ${dp(Geo.LARGE_W_DP)}x${dp(Geo.LARGE_H_DP)}px" }
                    place(screen, splash, splash.measuredWidth, splash.measuredHeight,
                        sphereRect.left - splash.measuredWidth - 8, (sphereRect.centerY() - splash.measuredHeight / 2).coerceAtLeast(0))
                    place(screen, sphereWin(p, rec = true), dp(64), dp(64), sphereX, sphereY)
                    place(screen, DropBar(ctx, p), W, dp(56), 0, H - dp(56))
                    File(dir, "scene-a-$theme-$bg.png").outputStream().use { draw(screen).compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                // Scene B: sphere + after-capture popup (Text lit, first-run hint labels) 
                run {
                    val screen = FrameLayout(ctx)
                    place(screen, background(bg), W, H, 0, 0)
                    val popup = popupRow(p, hint = true)
                    popup.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    place(screen, popup, popup.measuredWidth, popup.measuredHeight,
                        (sphereRect.left - popup.measuredWidth - 8).coerceAtLeast(0), (sphereRect.centerY() - popup.measuredHeight / 2).coerceAtLeast(0))
                    place(screen, sphereWin(p, rec = false), dp(64), dp(64), sphereX, sphereY)
                    File(dir, "scene-b-$theme-$bg.png").outputStream().use { draw(screen).compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
        println("scenes written to ${dir.absolutePath}")
    }
}
