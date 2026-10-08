package net.gaiadesk.embed.internal

import android.graphics.RectF
import net.gaiadesk.embed.GaiaDeskEmbed
import org.json.JSONArray
import org.json.JSONObject

/** The JSON the library speaks, and the small pure decisions around it. JVM-tested. */
internal object EmbedJson {
    fun config(c: GaiaDeskEmbed.Configuration, consentGranted: Boolean): String {
        val o = JSONObject()
            .put("embed_token", c.embedToken)
            .put("consent_granted", consentGranted)
            .put("company", c.company)
            .put("capture", c.capture.wire)
            .put("guided", c.guided)
            .put("fps", c.fps)
        c.server?.let { o.put("server", it) }
        if (c.maskedRects.isNotEmpty()) {
            val a = JSONArray()
            for (r in c.maskedRects) {
                a.put(JSONObject().put("x", r.left.toDouble()).put("y", r.top.toDouble()).put("w", (r.right - r.left).toDouble()).put("h", (r.bottom - r.top).toDouble()))
            }
            o.put("masks", a)
        }
        return o.toString()
    }

    fun flatten(rects: List<RectF>): DoubleArray {
        val out = DoubleArray(rects.size * 4)
        rects.forEachIndexed { i, r ->
            out[i * 4] = r.left.toDouble()
            out[i * 4 + 1] = r.top.toDouble()
            out[i * 4 + 2] = (r.right - r.left).toDouble()
            out[i * 4 + 3] = (r.bottom - r.top).toDouble()
        }
        return out
    }

    fun error(json: String): Pair<String, String> = try {
        val o = JSONObject(json)
        o.optString("code", "unknown") to o.optString("message", "")
    } catch (e: Exception) {
        "unknown" to json
    }

    /** One event's JSON → an [GaiaDeskEmbed.Event], or null for a kind this version does not know. */
    fun event(json: String): GaiaDeskEmbed.Event? {
        val o = try { JSONObject(json) } catch (e: Exception) { return null }
        fun s(k: String): String? = if (o.isNull(k)) null else o.optString(k)
        return when (o.optString("type")) {
            "state" -> GaiaDeskEmbed.Event.State(o.optString("state"))
            "code" -> GaiaDeskEmbed.Event.Code(o.optString("code"), o.optString("session_id"), o.optString("mode", "view"))
            "joined" -> GaiaDeskEmbed.Event.Joined(s("agent"))
            "left" -> GaiaDeskEmbed.Event.Left
            "control" -> GaiaDeskEmbed.Event.Control(o.optBoolean("allowed"))
            "action" -> GaiaDeskEmbed.Event.Action(o.optString("action"), o.optBoolean("applied"), s("reason"))
            "error" -> GaiaDeskEmbed.Event.Error(o.optString("code"), o.optString("message"))
            "ended" -> GaiaDeskEmbed.Event.Ended(o.optString("reason"))
            else -> null
        }
    }

    fun consentText(company: String, guided: Boolean, capture: GaiaDeskEmbed.Capture): Pair<String, String> {
        val what = if (capture == GaiaDeskEmbed.Capture.SCREEN) "your screen" else "this app's screen"
        var message = "$company's support team will see $what until you press Stop."
        message += if (capture == GaiaDeskEmbed.Capture.SCREEN) " Android will ask you to confirm." else " Nothing outside this app is shared."
        if (guided) message += " They may also tap and type in this app; you can pause that at any time."
        return "Share $what with $company support?" to message
    }

    /** The picture's size for a window of [w]×[h] pixels: at most [maxSide], both sides multiples of 16 (hardware encoders want it). */
    fun fit(w: Int, h: Int, maxSide: Int = 1280): Pair<Int, Int> {
        val longest = maxOf(w, h).coerceAtLeast(1)
        val s = minOf(1.0, maxSide.toDouble() / longest)
        val fw = ((w * s).toInt() / 16 * 16).coerceAtLeast(16)
        val fh = ((h * s).toInt() / 16 * 16).coerceAtLeast(16)
        return fw to fh
    }

    /** The console's keys as Android key codes (KeyEvent.KEYCODE_*), or null. */
    fun keyCode(key: String): Int? = when (key) {
        "Enter" -> 66 // KEYCODE_ENTER
        "Tab" -> 61 // KEYCODE_TAB
        "Backspace" -> 67 // KEYCODE_DEL
        "Delete" -> 112 // KEYCODE_FORWARD_DEL
        "Escape" -> 111 // KEYCODE_ESCAPE
        "ArrowUp" -> 19 // KEYCODE_DPAD_UP
        "ArrowDown" -> 20
        "ArrowLeft" -> 21
        "ArrowRight" -> 22
        "Home" -> 122 // KEYCODE_MOVE_HOME
        "End" -> 123 // KEYCODE_MOVE_END
        else -> null
    }
}
