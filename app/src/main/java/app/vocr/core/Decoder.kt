package app.vocr.core

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Sequential hardware decode: one pass over the stream, no per-frame seeking.
 * Calls onFrame for roughly `fps` frames per second of video, with a compact YUV copy.
 * Returns false if the stream could not be decoded this way (caller falls back).
 */
object Decoder {
    class Info(val width: Int, val height: Int, val durationUs: Long, val rotation: Int)

    private fun open(ctx: Context, uri: Uri?): MediaExtractor {
        val ex = MediaExtractor()
        if (uri != null) ex.setDataSource(ctx, uri, null)
        else ctx.assets.openFd("demo.mp4").use { ex.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        return ex
    }

    fun probe(ctx: Context, uri: Uri?): Info? {
        val ex = open(ctx, uri)
        try {
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    return Info(f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT),
                        if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L,
                        if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0)
                }
            }
        } finally { ex.release() }
        return null
    }

    private fun copy(img: Image): YuvFrame {
        val w = img.width; val h = img.height
        val (py, pu, pv) = img.planes.let { Triple(it[0], it[1], it[2]) }
        val y = ByteArray(w * h)
        val yb = py.buffer
        for (r in 0 until h) { yb.position(r * py.rowStride); yb.get(y, r * w, w) }
        val cw = w / 2; val ch = h / 2
        fun plane(p: Image.Plane): ByteArray {
            val out = ByteArray(cw * ch); val b = p.buffer
            if (p.pixelStride == 1) { for (r in 0 until ch) { b.position(r * p.rowStride); b.get(out, r * cw, cw) } }
            else {
                val row = ByteArray(minOf(p.rowStride, b.limit()))
                for (r in 0 until ch) {
                    b.position(r * p.rowStride); val n = minOf(row.size, b.remaining()); b.get(row, 0, n)
                    for (c in 0 until cw) out[r * cw + c] = row[c * p.pixelStride]
                }
            }
            return out
        }
        return YuvFrame(w, h, y, plane(pu), plane(pv))
    }

    fun decode(ctx: Context, uri: Uri?, fps: Double, cancelled: () -> Boolean,
               onFrame: (tSec: Double, f: YuvFrame) -> Double): Boolean {
        val ex = open(ctx, uri)
        var codec: MediaCodec? = null
        try {
            var track = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return false
            ex.selectTrack(track)
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var nextUs = 0L; var stepUs = (1_000_000 / fps).toLong()
            while (!outputDone && !cancelled()) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    if (info.size > 0 && info.presentationTimeUs >= nextUs) {
                        val img = codec.getOutputImage(oi)
                        if (img != null) {
                            val f = copy(img); img.close()
                            val want = onFrame(info.presentationTimeUs / 1e6, f)   // fps wanted from here on
                            stepUs = (1_000_000 / want.coerceIn(1.0, 30.0)).toLong()
                            nextUs = info.presentationTimeUs + stepUs
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
