package app.vocr.sphere

/**
 * Which engine action a sphere tap sends. Pure so the tap decisions are unit-testable.
 * Idle: quick capture needs two taps. While a capture or recording runs: the first tap means Done
 * (the caller delays it by the double-tap window so a second tap can turn it into Cancel), the second tap means Cancel.
 */
object SphereTools {
    /** Quick capture fires on the SECOND tap within [windowMs] of the first; a lone tap arms only. */
    fun secondTapFires(lastTapAt: Long, now: Long, windowMs: Long): Boolean =
        lastTapAt > 0L && now - lastTapAt in 0..windowMs

    /** Done: finish the active run and keep it. A capture saves; a recording stops and is read. */
    fun doneTap(shotActive: Boolean): String =
        if (shotActive) app.vocr.capture.CaptureService.ACTION_SHOT_FINISH else app.vocr.capture.CaptureService.ACTION_REC

    /** Cancel-discard: throws the active run away, saves nothing. Never the same action as Done. */
    fun cancelTap(recActive: Boolean): String =
        if (recActive) app.vocr.capture.CaptureService.ACTION_REC_CANCEL else app.vocr.capture.CaptureService.ACTION_SHOT_CANCEL

    /** Active-mode sphere tap: null when idle; first tap Done; second tap Cancel. */
    fun activeTapAction(secondTap: Boolean, shotActive: Boolean, recActive: Boolean): String? = when {
        !shotActive && !recActive -> null
        secondTap -> cancelTap(recActive)
        else -> doneTap(shotActive)
    }
}
