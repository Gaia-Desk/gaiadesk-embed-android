package net.gaiadesk.embed.internal

import java.nio.ByteBuffer

/**
 * libgaiadesk_embed.so's JNI entry points (embed/src/platform/android/jni.rs in GaiaDesk).
 * Handles are opaque; each is passed to [stop] exactly once (AndroidHost.detach guarantees it).
 */
internal object NativeBridge {
    init {
        System.loadLibrary("gaiadesk_embed")
    }

    /** A handle, or 0 when refused ([lastError] says why). */
    @JvmStatic external fun start(configJson: String, host: NativeHost): Long

    /** `{"code":…,"message":…}` of this thread's last refusal. */
    @JvmStatic external fun lastError(): String

    /** Ends the session and frees the handle; waits for it to wind down (never on the main thread). */
    @JvmStatic external fun stop(handle: Long)

    /** The customer pressed Stop on the indicator: ends with "stopped". */
    @JvmStatic external fun customerStop(handle: Long)

    @JvmStatic external fun setPaused(handle: Long, paused: Boolean)

    /** x, y, w, h repeated, in window pixels. */
    @JvmStatic external fun setMasks(handle: Long, rects: DoubleArray): Boolean

    /** RGBA, [height] rows of [stride] bytes in a direct buffer. 0 taken, 1 not capturing, -1 refused. */
    @JvmStatic external fun pushFrame(handle: Long, pixels: ByteBuffer, width: Int, height: Int, stride: Int, x: Double, y: Double, w: Double, h: Double): Int

    /** The answer to [NativeHost.deliver]'s [token]: [reason] null when applied. */
    @JvmStatic external fun actionResult(handle: Long, token: Long, reason: String?)

    @JvmStatic external fun version(): String

    @JvmStatic external fun buildInfo(): String
}

/**
 * The app's half, called by the library from its own threads (attached as daemons). Every method
 * hops to the main thread itself and must not block on it.
 */
internal interface NativeHost {
    fun onEvent(json: String)
    fun showIndicator(title: String, company: String, guided: Boolean): Boolean
    fun indicatorVisible(): Boolean
    fun setStatus(text: String)
    fun setIndicatorPaused(paused: Boolean)
    fun refreshIndicator()
    fun closeIndicator()
    fun startCapture(surface: String, fps: Int): Boolean
    fun stopCapture()
    /** The app's window in the pushed pictures' space (screen pixels): x, y, w, h; null if unknown. */
    fun appFrame(): DoubleArray?
    fun deliver(actionJson: String, token: Long)
    fun release()
}
