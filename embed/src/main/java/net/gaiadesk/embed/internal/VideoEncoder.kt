package net.gaiadesk.embed.internal

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * H.264 through Android's MediaCodec, for the library: it hands over the MASKED picture (RGBA;
 * masks are painted by the library before it gets here) and gets Annex-B access units back, each
 * behind a 4-byte big-endian length, with the parameter sets in front of every keyframe.
 *
 * The picture is drawn onto the encoder's input Surface with a hardware canvas — the path
 * Android's own screen recording uses. Writing YUV into the encoder's buffers by CPU (NDK byte
 * buffers, or `getInputImage`) is not reliable: the emulator's Codec2 encoder encoded empty
 * pictures from all of them. Called on the library's video thread.
 */
internal class VideoEncoder private constructor(
    private val codec: MediaCodec,
    private val surface: Surface,
    private val w: Int,
    private val h: Int,
) {
    private var config: ByteArray? = null
    private val info = MediaCodec.BufferInfo()
    private val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    fun encode(rgba: ByteBuffer, key: Boolean): ByteArray {
        if (key) codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        val out = ByteArrayOutputStream()
        drain(out, 0)
        rgba.rewind()
        bitmap.copyPixelsFromBuffer(rgba) // R,G,B,A bytes: ARGB_8888's memory order
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        drain(out, 50_000)
        return out.toByteArray()
    }

    private fun drain(out: ByteArrayOutputStream, firstWaitUs: Long) {
        var wait = firstWaitUs
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, wait)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (idx < 0) return
            wait = 0
            val buf = codec.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = bytes
                } else {
                    val unit = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 && !hasSps(bytes)) (config ?: ByteArray(0)) + bytes else bytes
                    out.write(byteArrayOf((unit.size ushr 24).toByte(), (unit.size ushr 16).toByte(), (unit.size ushr 8).toByte(), unit.size.toByte()))
                    out.write(unit)
                }
            }
            codec.releaseOutputBuffer(idx, false)
        }
    }

    fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
        bitmap.recycle()
    }

    companion object {
        /** An Annex-B buffer already carries an SPS (NAL type 7). Pure. */
        fun hasSps(b: ByteArray): Boolean {
            var i = 0
            while (i + 3 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                    if (b[i + 3].toInt() and 0x1f == 7) return true
                    i += 3
                } else {
                    i++
                }
            }
            return false
        }

        fun open(w: Int, h: Int, kbps: Int, fps: Int): VideoEncoder? = try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                // A still picture still produces frames: the session re-sends at least once a second.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            VideoEncoder(codec, surface, w, h)
        } catch (e: Exception) {
            android.util.Log.w("gaiadesk_embed", "no H.264 encoder for ${w}x$h: $e")
            null
        }
    }
}
