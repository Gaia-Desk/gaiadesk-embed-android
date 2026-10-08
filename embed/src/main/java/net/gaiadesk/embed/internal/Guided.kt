package net.gaiadesk.embed.internal

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.EditText
import org.json.JSONObject

/**
 * Guided input, delivered INSIDE the app: events dispatched to the resumed activity's own window
 * (`Activity.dispatchTouchEvent` / `dispatchKeyEvent` / `dispatchGenericMotionEvent`), never
 * through the system's input stream, so nothing outside this app can receive one and no
 * permission is involved.
 *
 * The library has already checked view-only/pause/rate and that a point is inside the window and
 * not masked. Checked here on the main thread at the moment of delivery: an activity of this app is
 * resumed (`not_focused`) and the point is not the indicator (`embed_ui`).
 *
 *   click   a tap (down + up) at the point, as a touchscreen.
 *   scroll  a mouse-wheel scroll at the point (lists, scroll views and Compose scroll on it).
 *   type    key events for the text (KeyCharacterMap); text no key map can type goes straight
 *           into a focused EditText. Nothing focused: `not_editable`.
 *   key     Enter, Tab, Backspace, Delete, Escape, arrows, Home, End.
 *   pointer the agent's pointer; highlight a ring.
 */
internal object Guided {
    /** Deliver one request; null when applied, else why not. Main thread. */
    fun deliver(json: String, activity: Activity?, overlay: IndicatorOverlay?): String? {
        val o = try { JSONObject(json) } catch (e: Exception) { return "unsupported" }
        val a = activity ?: return "not_focused"
        val x = o.optDouble("x", 0.0).toFloat()
        val y = o.optDouble("y", 0.0).toFloat()
        return when (o.optString("t")) {
            "pointer" -> null.also { overlay?.pointer(x, y) }
            "highlight" -> null.also { overlay?.ring(x, y, null) }
            "click" -> if (overlay?.onPill(x, y) == true) "embed_ui" else tap(a, x, y)
            "scroll" -> if (overlay?.onPill(x, y) == true) "embed_ui" else scroll(a, x, y, o.optDouble("dx", 0.0).toFloat(), o.optDouble("dy", 0.0).toFloat())
            "type" -> type(a, o.optString("text"))
            "key" -> key(a, o.optString("key"))
            else -> "unsupported"
        }
    }

    fun tap(a: Activity, x: Float, y: Float): String? {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            a.dispatchTouchEvent(down)
            a.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        return null
    }

    fun scroll(a: Activity, x: Float, y: Float, dx: Float, dy: Float): String? {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            // The console sends wheel pixels; Android's wheel unit is a notch (~64 px).
            setAxisValue(MotionEvent.AXIS_VSCROLL, (-dy / 64f).coerceIn(-30f, 30f))
            setAxisValue(MotionEvent.AXIS_HSCROLL, (dx / 64f).coerceIn(-30f, 30f))
        })
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        return try {
            if (a.dispatchGenericMotionEvent(ev)) null else "nothing_there"
        } finally {
            ev.recycle()
        }
    }

    fun type(a: Activity, text: String): String? {
        val focus = a.currentFocus ?: return "not_editable"
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        if (events != null) {
            events.forEach { a.dispatchKeyEvent(it) }
            return null
        }
        val edit = focus as? EditText ?: return "not_editable"
        val e = edit.text
        val start = edit.selectionStart.coerceAtLeast(0)
        val end = edit.selectionEnd.coerceAtLeast(0)
        e.replace(minOf(start, end), maxOf(start, end), text)
        return null
    }

    fun key(a: Activity, key: String): String? {
        val code = EmbedJson.keyCode(key) ?: return "unsupported"
        if (a.currentFocus == null && code != KeyEvent.KEYCODE_ESCAPE) return "not_editable"
        val t = SystemClock.uptimeMillis()
        a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        a.dispatchKeyEvent(KeyEvent(t, t + 20, KeyEvent.ACTION_UP, code, 0))
        return null
    }
}
