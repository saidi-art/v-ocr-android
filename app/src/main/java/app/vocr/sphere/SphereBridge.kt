package app.vocr.sphere

/**
 * In-process link between the capture engine (CaptureService / QueueService) and the Sphere shell (SphereService).
 * The engine reports a finished capture; the shell shows the after-capture popup. Both services live in the same
 * process, so a listener is enough - no broadcasts, no persistence, no network.
 */
object SphereBridge {
    interface Listener {
        /** A capture finished and is saved in Sphere's folder: show the four minis. */
        fun onCaptureReady(m: CaptureStore.Meta)
        /** A capture failed honestly (nothing silently lost): the shell may say so and log the step. */
        fun onCaptureFailed(kind: CaptureStore.CaptureType, reason: String)
        /** REC started or stopped: the sphere carries the red dot while it runs. */
        fun onRecState(active: Boolean) {}
    }

    @Volatile var listener: Listener? = null

    fun ready(m: CaptureStore.Meta) { listener?.onCaptureReady(m) }
    fun failed(kind: CaptureStore.CaptureType, reason: String) { listener?.onCaptureFailed(kind, reason) }
    fun recState(active: Boolean) { listener?.onRecState(active) }
}
