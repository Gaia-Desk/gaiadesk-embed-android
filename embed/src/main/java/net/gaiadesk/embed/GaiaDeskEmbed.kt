// GaiaDesk embed for Android: share your app's screen with your support team
// from a "Get help" button. A Kotlin layer over libgaiadesk_embed.
//
// What the library enforces (this layer cannot skip it): it refuses to start
// without consent; frames are encoded and sent only while this layer's
// "Sharing with <company>" indicator reports itself on screen; the rects you
// mask are painted out of every frame before it is encoded; input is off
// unless you allow `guided` AND the session is "cobrowse", and a request
// outside your window or on a mask is refused before it reaches you.
// What this layer does: the indicator (a view in your activity), capturing
// ONLY your app's window (PixelCopy; no permission, no system dialog) or, if
// you opt in, the screen (MediaProjection, with Android's own consent), and
// delivering guided input to your own views.
package net.gaiadesk.embed

import android.app.Activity
import android.app.AlertDialog
import android.graphics.RectF
import android.view.View
import net.gaiadesk.embed.internal.AndroidHost
import net.gaiadesk.embed.internal.EmbedJson
import net.gaiadesk.embed.internal.NativeBridge
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A refusal from the library: [code] is stable (`consent_required`, `bad_token`, `busy`, …). */
class GaiaDeskEmbedException(val code: String, override val message: String) : Exception("$code: $message")

class GaiaDeskEmbed private constructor(handle: Long, private val host: AndroidHost) {
    /** What is shared. */
    enum class Capture(val wire: String) {
        /** Your app's own window (the default): no permission, no system dialog. */
        APP("app"),

        /** The whole screen, through MediaProjection: Android asks the user too. Opt-in. */
        SCREEN("screen"),
    }

    data class Configuration(
        /** `gdemb_…`, from your backend (`POST /v1/support/sessions`). */
        val embedToken: String,
        /** Shown on the consent dialog and the indicator. */
        val company: String,
        /** Default `https://gaiadesk.net`. */
        val server: String? = null,
        /** Allow guided input (the session must be "cobrowse" too). */
        val guided: Boolean = false,
        /** 1 to 30. */
        val fps: Int = 15,
        /** Regions painted out from the very first frame: window pixels ([maskedRectOf]). */
        val maskedRects: List<RectF> = emptyList(),
        val capture: Capture = Capture.APP,
        /** Views painted out wherever they are, measured with every frame (password fields are masked anyway). */
        val maskedViews: List<View> = emptyList(),
    )

    /**
     * Your user's YES to a consent dialog, bound to what it asked: who sees, what is shared,
     * whether the agent may guide. [start] only starts a session that matches it, once, within
     * 10 minutes. From [requestConsent], or [recordConsent] after your own dialog.
     */
    class Consent internal constructor(val token: String, val company: String, val capture: Capture, val guided: Boolean)

    sealed class Event {
        data class State(val state: String) : Event()
        data class Code(val code: String, val sessionId: String, val mode: String) : Event()
        data class Joined(val agent: String?) : Event()
        object Left : Event()
        data class Control(val allowed: Boolean) : Event()
        data class Action(val action: String, val applied: Boolean, val reason: String?) : Event()
        data class Error(val code: String, val message: String) : Event()
        data class Ended(val reason: String) : Event()
    }

    private val handle = AtomicLong(handle)

    /** End the session (`Ended("app_stopped")` follows). Safe to call again; returns at once. */
    fun stop() {
        val h = host.detach(handle) ?: return
        stopper.execute { NativeBridge.stop(h) }
    }

    /** Mask these rects (window pixels; [maskedRectOf]). Replaces any earlier set. */
    fun setMaskedRects(rects: List<RectF>) {
        host.appRects = rects.map { doubleArrayOf(it.left.toDouble(), it.top.toDouble(), (it.right - it.left).toDouble(), (it.bottom - it.top).toDouble()) }
        host.withHandle(handle) { h ->
            if (!NativeBridge.setMasks(h, EmbedJson.flatten(rects))) throw lastError()
        }
    }

    /**
     * Mask these views (card numbers, balances) wherever they are: measured on the main thread
     * with every frame and painted out of it. Replaces any earlier set. Password fields
     * (EditText with a password inputType) are masked without being listed.
     */
    fun setMaskedViews(views: List<View>) = host.masks.setViews(views)

    /**
     * Pause (or resume) guided input. False when refused: you cannot resume a pause your
     * customer made on the indicator — only they can.
     */
    fun setPaused(paused: Boolean): Boolean {
        var ok = false
        host.withHandle(handle) { h -> ok = NativeBridge.setPaused(h, paused) }
        return ok
    }

    companion object {
        private val stopper = Executors.newSingleThreadExecutor { r -> Thread(r, "gd-embed-stop").apply { isDaemon = true } }

        /** The library's version ("0.1.0"). */
        val version: String get() = NativeBridge.version()

        /** What the bundled binary is (JSON: version, codec, os, arch, test_source). */
        val buildInfo: String get() = NativeBridge.buildInfo()

        /** The session running in this app, if any (one at a time). */
        @Volatile
        var current: GaiaDeskEmbed? = null
            private set

        /**
         * Start sharing. [consent] is your user's YES to a consent dialog ([requestConsent], or
         * your own and [recordConsent]); the library refuses a session that differs from what they
         * agreed to (company, capture, guided). [onEvent] is called on the main thread. Call on the
         * main thread, from the activity to share.
         */
        @JvmStatic
        fun start(activity: Activity, config: Configuration, consent: Consent, onEvent: (Event) -> Unit): GaiaDeskEmbed {
            val self = arrayOfNulls<GaiaDeskEmbed>(1)
            val host = AndroidHost(activity, config.capture) { e ->
                if (e is Event.Ended && current === self[0]) current = null
                onEvent(e)
            }
            host.masks.setViews(config.maskedViews)
            host.appRects = config.maskedRects.map { doubleArrayOf(it.left.toDouble(), it.top.toDouble(), (it.right - it.left).toDouble(), (it.bottom - it.top).toDouble()) }
            val h = NativeBridge.start(EmbedJson.config(config, consent.token), host)
            if (h == 0L) throw lastError()
            val embed = GaiaDeskEmbed(h, host)
            self[0] = embed
            host.attach(embed.handle)
            current = embed
            return embed
        }

        /** The consent dialog's wording. */
        @JvmStatic
        fun consentText(company: String, guided: Boolean, capture: Capture = Capture.APP): Pair<String, String> =
            EmbedJson.consentText(company, guided, capture)

        /** Ask the user (an AlertDialog); [answer] gets their [Consent] on YES, null on no, on the main thread. */
        @JvmStatic
        fun requestConsent(activity: Activity, company: String, guided: Boolean = false, capture: Capture = Capture.APP, answer: (Consent?) -> Unit) {
            val (title, message) = consentText(company, guided, capture)
            var answered = false
            fun once(v: Boolean) {
                if (!answered) {
                    answered = true
                    answer(if (v) recordConsent(company, guided, capture) else null)
                }
            }
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Share") { _, _ -> once(true) }
                .setNegativeButton("Don't share") { _, _ -> once(false) }
                .setOnCancelListener { once(false) }
                .show()
        }

        /**
         * A [Consent] from a token recorded earlier (`recordConsent` in a wrapper that hands the
         * token across, e.g. React Native). The library still checks the token against the session.
         */
        @JvmStatic
        fun consent(token: String, company: String, guided: Boolean = false, capture: Capture = Capture.APP): Consent =
            Consent(token, company, capture, guided)

        /**
         * Your own consent dialog said YES to sharing [capture] with [company] (and, if [guided],
         * to being guided): its [Consent] for [start]. Say exactly that in your dialog.
         */
        @JvmStatic
        fun recordConsent(company: String, guided: Boolean = false, capture: Capture = Capture.APP): Consent {
            val token = NativeBridge.recordConsent(company, capture.wire, guided) ?: throw lastError()
            return Consent(token, company, capture, guided)
        }

        /** A view's frame in its window, in pixels: the space mask rects are in. */
        @JvmStatic
        fun maskedRectOf(view: View): RectF? {
            if (!view.isAttachedToWindow) return null
            val at = IntArray(2)
            view.getLocationInWindow(at)
            return RectF(at[0].toFloat(), at[1].toFloat(), (at[0] + view.width).toFloat(), (at[1] + view.height).toFloat())
        }

        private fun lastError(): GaiaDeskEmbedException {
            val (code, message) = EmbedJson.error(NativeBridge.lastError())
            return GaiaDeskEmbedException(code, message)
        }
    }
}
