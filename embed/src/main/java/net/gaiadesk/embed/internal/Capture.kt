package net.gaiadesk.embed.internal

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A source of RGBA pictures for the library. */
internal interface Capture {
    /** `done` says (once) whether frames will come. */
    fun start(fps: Int, done: (Boolean) -> Unit)
    fun stop()
}

/** Where pictures go: the host pushes them through the live handle. */
internal fun interface FrameSink {
    /** False: the library is not capturing any more. */
    fun push(pixels: ByteBuffer, width: Int, height: Int, stride: Int, picture: DoubleArray): Boolean
}

/**
 * The app's own window, copied by PixelCopy (API 26) from the activity the host says is in front,
 * scaled by the copy itself into a 16-aligned bitmap. No permission and no system dialog: it is
 * the app reading its own window. Dialogs and popups in windows of their own are not in it.
 */
internal class WindowCapture(private val activity: () -> Activity?, private val sink: FrameSink) : Capture {
    private val thread = HandlerThread("gd-embed-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var bitmap: Bitmap? = null
    private var buffer: ByteBuffer? = null
    @Volatile private var running = false
    @Volatile private var inFlight = false
    private var interval = 66L

    override fun start(fps: Int, done: (Boolean) -> Unit) {
        interval = 1000L / fps.coerceIn(1, 30)
        running = true
        handler.post(::tick)
        done(true)
    }

    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private fun tick() {
        if (!running) return
        handler.postDelayed(::tick, interval)
        if (inFlight) return
        val a = activity() ?: return
        val decor = a.window?.decorView ?: return
        if (!decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) return
        val (w, h) = EmbedJson.fit(decor.width, decor.height)
        val bm = bitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            bitmap = it
            buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        }
        val at = IntArray(2)
        decor.getLocationOnScreen(at)
        val picture = doubleArrayOf(at[0].toDouble(), at[1].toDouble(), decor.width.toDouble(), decor.height.toDouble())
        inFlight = true
        try {
            PixelCopy.request(a.window, bm, { result ->
                inFlight = false
                if (result == PixelCopy.SUCCESS && running) send(bm, picture)
            }, handler)
        } catch (e: IllegalArgumentException) {
            inFlight = false // the window has no surface yet
        }
    }

    private fun send(bm: Bitmap, picture: DoubleArray) {
        val buf = buffer ?: return
        buf.rewind()
        bm.copyPixelsToBuffer(buf) // ARGB_8888 is R,G,B,A in memory
        sink.push(buf, bm.width, bm.height, bm.width * 4, picture)
    }
}
