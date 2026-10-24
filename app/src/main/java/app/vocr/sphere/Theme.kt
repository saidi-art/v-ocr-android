package app.vocr.sphere

import android.graphics.Color
import app.vocr.capture.SphereShade

/**
 * Sphere v1 design tokens (Development Brief v2, "Design system" + "Motion").
 * One geometry, two materials: every shape, size and touch area is shared; a theme swaps only colour, finish,
 * light, icon ink and motion feel. Day = vivid daily colour; Night desk = banker's lamp (emerald + brass).
 * Exact night-desk finish and day satin/gloss balance are open brief decisions to confirm on device; the values
 * below are the working tokens, all in one place so they can be tuned without touching geometry or behaviour.
 */

/** Theme choice shown in Sphere settings. SYSTEM follows the phone's dark mode (Day in light, Night desk in dark). */
enum class ThemeChoice { DAY, NIGHT_DESK, SYSTEM }

/** Shared geometry in dp (brief table). All ovals are exactly 1.25:1; the sphere is round. */
object Geo {
    const val SPHERE_DP = 44;   const val SPHERE_TOUCH_DP = 48
    const val LARGE_W_DP = 50;  const val LARGE_H_DP = 40;  const val LARGE_ICON_DP = 18   // Rescue chat
    const val MED_W_DP = 40;    const val MED_H_DP = 32;    const val MED_ICON_DP = 16     // REC, Auto-scroll
    const val SMALL_W_DP = 30;  const val SMALL_H_DP = 24;  const val SMALL_ICON_DP = 14   // popup minis, Settings
    const val TRACK_W_DP = 140; const val TRACK_H_DP = 16;  const val TRACK_R_DP = 8       // Speed
    const val MIN_TOUCH_DP = 44                                                        // nothing smaller is touchable
}

/** Motion in ms. All durations come from one Fibonacci scale (brief). */
object Motions {
    const val F34 = 34L; const val F55 = 55L; const val F89 = 89L; const val F144 = 144L; const val F233 = 233L; const val F377 = 377L
    const val LONG_PRESS = F377          // long press fires at 377 ms
    const val STAGGER = F55              // tools/minis pop out staggered 55 ms
    const val POPUP_VANISH = 3000L       // minis vanish about 3 s after the last touch (2-4 s band)
    const val DOCK_AFTER = 5000L         // after 5 s idle the sphere docks
    const val DOCK_FRACTION = 0.40f      // ... sliding 40% off its edge
    const val POPUP_VANISH_MIN = 2000L; const val POPUP_VANISH_MAX = 4000L
}

/** Spring constants (brief: Reanimated stiffness/damping) mapped onto Android's SpringForce 1:1. */
object Springs {
    const val POP_STIFF = 377f;    const val POP_DAMP = 0.70f     // tools and minis appearing, lit state, re-fan after drag
    const val SETTLE_STIFF = 233f; const val SETTLE_DAMP = 0.90f  // dismiss, edge snap, docking, bar slide-in
    const val TRAIL_STIFF = 144f;  const val TRAIL_DAMP = 0.60f   // open tools following the sphere while dragged
}

/** Haptics (brief). Constants of android.view.HapticFeedbackConstants, named here so call sites read as the brief. */
object Haptic {
    const val CAPTURE = android.view.HapticFeedbackConstants.CONTEXT_CLICK
    const val SPLASH = android.view.HapticFeedbackConstants.LONG_PRESS
    const val READY = android.view.HapticFeedbackConstants.CONFIRM        // capture ready and hand-off
    const val PICK = android.view.HapticFeedbackConstants.KEYBOARD_TAP    // picking a format
}

/**
 * A resolved palette: every colour the shell needs for one theme on one day. Day hues come from the daily OKLCH
 * hue (day x golden angle, SphereShade) with the brief's fixed offsets; Night desk is a fixed material set.
 * [sphere], [rescue], [rec], [auto], [mini] are [highlight, body, shadow] triplets for a glossy ball/oval render.
 */
class Palette private constructor(
    val night: Boolean,
    val sphere: IntArray, val rescue: IntArray, val rec: IntArray, val auto: IntArray, val mini: IntArray,
    val settingsBg: Int, val barBg: Int, val halo: Int, val ink: Int, val inkOnGlass: Int,
    val trim: Int, val glow: Int
) {
    companion object {
        private const val INK_DAY = 0xFF191919.toInt()
        private const val CREAM = 0xFFF3E7CB.toInt()          // brief: cream ink on emerald and smoked glass
        private const val CHARCOAL = 0xFF2A2A2E.toInt()       // the auto-capture bar
        private const val BRASS_HI = 0xFFE3C88E.toInt(); private const val BRASS = 0xFFB08D57.toInt(); private const val BRASS_LO = 0xFF6F5433.toInt()
        private const val EMERALD_HI = 0xFF2E9E72.toInt(); private const val EMERALD = 0xFF0F6B4C.toInt(); private const val EMERALD_LO = 0xFF073B2A.toInt()
        private const val SMOKED = 0xCC38342F.toInt()         // smoked glass (translucent)
        private const val CLEAR_GLASS = 0x59FFFFFF            // clear glass bead (Settings, Day)
        private const val WARM_GLOW = 0xFFFFC978.toInt()      // the lamp's warm inner glow

        private fun dayHue(dayOfYear: Int): Double = SphereShade.hueOf(dayOfYear)

        /** Day theme: the sphere takes the day's hue; every other part sits at a fixed offset from it. */
        fun day(dayOfYear: Int): Palette {
            val h = dayHue(dayOfYear)
            return Palette(
                night = false,
                sphere = SphereShade.big(dayOfYear),
                rescue = SphereShade.tool(0.72, 0.16, h + 120.0),
                rec = SphereShade.tool(0.72, 0.16, h + 300.0),
                auto = SphereShade.tool(0.72, 0.16, h + 240.0),
                mini = SphereShade.tool(0.78, 0.10, h + 180.0),   // the complement, softened
                settingsBg = CLEAR_GLASS,
                barBg = CHARCOAL,
                halo = SphereShade.oklch(0.80, 0.10, h + 180.0),
                ink = INK_DAY, inkOnGlass = INK_DAY,
                trim = Color.WHITE, glow = Color.WHITE
            )
        }

        /** Night desk: emerald cased glass + brass; the sphere does not change colour by day. */
        fun nightDesk(): Palette = Palette(
            night = true,
            sphere = intArrayOf(EMERALD_HI.toInt(), EMERALD.toInt(), EMERALD_LO.toInt()),
            rescue = intArrayOf(EMERALD_HI.toInt(), EMERALD.toInt(), EMERALD_LO.toInt()),   // the only other emerald piece
            rec = intArrayOf(BRASS_HI.toInt(), BRASS.toInt(), BRASS_LO.toInt()),
            auto = intArrayOf(BRASS_HI.toInt(), BRASS.toInt(), BRASS_LO.toInt()),
            mini = intArrayOf(BRASS_HI.toInt(), BRASS.toInt(), BRASS_LO.toInt()),
            settingsBg = SMOKED.toInt(),
            barBg = CHARCOAL,
            halo = WARM_GLOW.toInt(),
            ink = INK_DAY,                 // dark ink on brass
            inkOnGlass = CREAM,            // cream on emerald and smoked glass
            trim = BRASS.toInt(), glow = WARM_GLOW.toInt()
        )

        fun resolve(choice: ThemeChoice, systemNight: Boolean, dayOfYear: Int): Palette = when (choice) {
            ThemeChoice.DAY -> day(dayOfYear)
            ThemeChoice.NIGHT_DESK -> nightDesk()
            ThemeChoice.SYSTEM -> if (systemNight) nightDesk() else day(dayOfYear)
        }
    }
}
