package net.gaiadesk.embed.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import net.gaiadesk.embed.GaiaDeskEmbed
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The library's view of this layer ([NativeHost]): every call lands here on one of the library's
 * threads and hops to the main thread for UI. The two answers the library polls (is the indicator
 * visible, where is the app's window) are kept current by the main thread four times a second, so
 * they never wait on it.
 *
 * The session follows the app, not one activity: the indicator (and the window capture) move to
 * whichever of the app's activities is resumed; with none resumed (the app in the background)
 * nothing is visible, so the library sends nothing.
 */
internal class AndroidHost(
    first: Activity,
    private val capture: GaiaDeskEmbed.Capture,
    private val listener: (GaiaDeskEmbed.Event) -> Unit,
) : NativeHost {
    private val app = first.application
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var handleRef: AtomicLong? = null
    @Volatile private var resumed: Activity? = first
    @Volatile private var visible = false
    @Volatile private var frame: DoubleArray? = null
    private var overlay: IndicatorOverlay? = null
    private var source: Capture? = null
    private val released = AtomicBoolean(false)

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) {
            if (a is ProjectionRequestActivity) return
            resumed = a
            overlay?.attachTo(a)
        }
        override fun onActivityPaused(a: Activity) {
            if (resumed === a) resumed = null
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {
            if (resumed === a) resumed = null
        }
    }

    private val watch = object : Runnable {
        override fun run() {
            observe()
            main.postDelayed(this, 250)
        }
    }

    init {
        app.registerActivityLifecycleCallbacks(lifecycle)
    }

    fun attach(h: AtomicLong) = synchronized(lock) { handleRef = h }

    /** The app stopped: no frame or answer reaches the handle after this. Returns it, once. */
    fun detach(h: AtomicLong): Long? = synchronized(lock) {
        val v = h.getAndSet(0)
        main.post { source?.stop(); source = null }
        v.takeIf { it != 0L }
    }

    /** Run [f] with the live handle, under the lock [detach] takes. */
    fun withHandle(h: AtomicLong, f: (Long) -> Unit) = synchronized(lock) {
        val v = h.get()
        if (v != 0L) f(v)
    }

    private fun live(f: (Long) -> Unit) {
        val h = handleRef ?: return
        withHandle(h, f)
    }

    // ── NativeHost (library threads) ───────────────────────────────────

    override fun onEvent(json: String) {
        val e = EmbedJson.event(json) ?: return
        main.post { listener(e) }
    }

    override fun showIndicator(title: String, company: String, guided: Boolean): Boolean {
        main.post {
            if (overlay != null) return@post
            val o = IndicatorOverlay(app, title, guided, onStop = { live { NativeBridge.customerStop(it) } }, onPause = { p -> live { NativeBridge.setPaused(it, p) } })
            overlay = o
            resumed?.let { o.attachTo(it) }
            main.removeCallbacks(watch)
            main.post(watch)
        }
        return true
    }

    override fun indicatorVisible(): Boolean = visible

    override fun setStatus(text: String) {
        main.post { overlay?.status = text }
    }

    override fun setIndicatorPaused(paused: Boolean) {
        main.post { overlay?.paused = paused }
    }

    override fun refreshIndicator() {
        main.post { resumed?.let { overlay?.attachTo(it) } }
    }

    override fun closeIndicator() {
        main.post {
            main.removeCallbacks(watch)
            visible = false
            overlay?.detach()
            overlay = null
        }
    }

    /** Waits for the capture's answer (the library allows 15 s; for SCREEN Android may be asking). */
    override fun startCapture(surface: String, fps: Int): Boolean {
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val sink = FrameSink { px, w, h, stride, pic -> push(px, w, h, stride, pic) }
        main.post {
            val c = if (surface == "screen" && capture == GaiaDeskEmbed.Capture.SCREEN) ScreenCapture(app, sink) else WindowCapture({ resumed }, sink)
            source = c
            c.start(fps) { started ->
                ok.set(started)
                done.countDown()
            }
        }
        done.await(14, TimeUnit.SECONDS)
        return ok.get()
    }

    override fun stopCapture() {
        main.post {
            source?.stop()
            source = null
        }
    }

    override fun appFrame(): DoubleArray? = frame

    override fun deliver(actionJson: String, token: Long) {
        main.post {
            val reason = Guided.deliver(actionJson, resumed, overlay)
            if (token != 0L) live { NativeBridge.actionResult(it, token, reason) }
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        main.post {
            main.removeCallbacks(watch)
            source?.stop()
            source = null
            overlay?.detach()
            overlay = null
            visible = false
            app.unregisterActivityLifecycleCallbacks(lifecycle)
        }
    }

    // ── main thread ───────────────────────────────────────────────────

    private fun observe() {
        val a = resumed
        visible = overlay?.isVisible(a) ?: false
        val decor = a?.window?.decorView
        frame = if (decor != null && decor.width > 0) {
            val at = IntArray(2)
            decor.getLocationOnScreen(at)
            doubleArrayOf(at[0].toDouble(), at[1].toDouble(), decor.width.toDouble(), decor.height.toDouble())
        } else {
            null
        }
    }

    private fun push(px: ByteBuffer, w: Int, h: Int, stride: Int, pic: DoubleArray): Boolean {
        var going = false
        live { going = NativeBridge.pushFrame(it, px, w, h, stride, pic[0], pic[1], pic[2], pic[3]) != 1 }
        return going
    }
}
