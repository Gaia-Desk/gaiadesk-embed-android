package net.gaiadesk.embed.internal

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
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
    /** False: the library is not capturing any more. `masks`: x, y, w, h repeated (window pixels). */
    fun push(pixels: ByteBuffer, width: Int, height: Int, stride: Int, picture: DoubleArray, masks: DoubleArray?): Boolean
}

/** What one capture is of, decided on the main thread at the moment of the copy. */
internal class Shot(
    val window: Window,
    /** Where the window is on screen (x, y, w, h, pixels). */
    val picture: DoubleArray,
    /** The masked views and password fields now (window pixels). */
    val masks: DoubleArray,
    /** The window is FLAG_SECURE: the app protects it from capture; send black. */
    val secure: Boolean,
)

/**
 * The app's own window, copied by PixelCopy (API 26) from the activity the host says is in front,
 * scaled by the copy itself into a 16-aligned bitmap. No permission and no system dialog: it is
 * the app reading its own window. Dialogs and popups in windows of their own are not in it.
 *
 * [prepare] runs on the main thread for every frame: it returns nothing while the window does not
 * have focus (another window of the app, the notification shade, another app is over it), and it
 * measures the masks with the copy, so they move with the picture.
 */
internal class WindowCapture(private val prepare: () -> Shot?, private val sink: FrameSink) : Capture {
    private val thread = HandlerThread("gd-embed-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
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
        inFlight = true
        main.post { copy() }
    }

    /** Main thread. */
    private fun copy() {
        val shot = if (running) prepare() else null
        if (shot == null) {
            inFlight = false
            return
        }
        val (w, h) = EmbedJson.fit(shot.picture[2].toInt(), shot.picture[3].toInt())
        val bm = bitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            bitmap = it
            buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        }
        if (shot.secure) {
            // Protected by the app: a black picture, never its pixels.
            handler.post {
                inFlight = false
                val buf = buffer ?: return@post
                buf.rewind()
                buf.put(ByteArray(w * h * 4))
                buf.rewind()
                if (running) sink.push(buf, w, h, w * 4, shot.picture, shot.masks)
            }
            return
        }
        try {
            PixelCopy.request(shot.window, bm, { result ->
                inFlight = false
                if (result == PixelCopy.SUCCESS && running) send(bm, shot)
            }, handler)
        } catch (e: IllegalArgumentException) {
            inFlight = false // the window has no surface yet
        }
    }

    private fun send(bm: Bitmap, shot: Shot) {
        val buf = buffer ?: return
        buf.rewind()
        bm.copyPixelsToBuffer(buf) // ARGB_8888 is R,G,B,A in memory
        sink.push(buf, bm.width, bm.height, bm.width * 4, shot.picture, shot.masks)
    }
}
