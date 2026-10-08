package net.gaiadesk.embed.internal

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Sharing with <company> · Stop": a pill at the top of the app's current activity, drawn in its
 * own window (a view added to the decor view: no SYSTEM_ALERT_WINDOW), above the app's content.
 * The rest of the overlay is touch-through and draws the agent's pointer and hints. Main thread.
 *
 * The library sends frames only while [isVisible] says so (polled by [AndroidHost]): attached to
 * the resumed activity, shown, opaque, fully on screen, the decor view's top child — and the host
 * adds that the activity's window has focus (no dialog, notification shade or other app over it).
 *
 * This check is COOPERATIVE: it runs in the app's own process, so an app that wanted to could hide
 * the pill and lie about it. What the library enforces regardless is the rest: consent bound to
 * the session, masks painted before encoding, guided input refused unless allowed.
 */
internal class IndicatorOverlay(context: Context, private val title: String, guided: Boolean, onStop: () -> Unit, onPause: (Boolean) -> Unit) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /** The full-window layer: never takes touches itself. */
    val root = object : FrameLayout(context) {
        override fun onInterceptTouchEvent(ev: android.view.MotionEvent?) = false
    }.apply {
        isClickable = false
        isFocusable = false
        elevation = dp(64).toFloat()
        translationZ = dp(64).toFloat()
        contentDescription = "gaiadesk.overlay"
    }
    private val marks = MarkLayer(context).apply { isClickable = false }
    val pill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.argb(245, 204, 26, 31))
            cornerRadius = dp(18).toFloat()
        }
        setPadding(dp(12), dp(4), dp(6), dp(4))
        elevation = dp(8).toFloat()
        contentDescription = "gaiadesk.indicator"
        isClickable = true // touches on the pill stop here
    }
    private val label = TextView(context).apply {
        text = title
        setTextColor(Color.WHITE)
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        contentDescription = "gaiadesk.indicator.status"
    }
    private val pause = button(context, "Pause control", "gaiadesk.indicator.pause")
    private val stop = button(context, "Stop", "gaiadesk.indicator.stop")
    var paused = false
        set(v) {
            field = v
            pause.text = if (v) "Allow control" else "Pause control"
        }

    init {
        pill.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (guided) pill.addView(pause)
        pill.addView(stop)
        stop.setOnClickListener { onStop() }
        pause.setOnClickListener {
            paused = !paused
            onPause(paused)
        }
        root.addView(marks, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(36)
            leftMargin = dp(8)
            rightMargin = dp(8)
        })
    }

    private fun button(context: Context, text: String, id: String) = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), dp(2), dp(10), dp(2))
        background = GradientDrawable().apply {
            setColor(Color.argb(56, 255, 255, 255))
            cornerRadius = dp(12).toFloat()
        }
        contentDescription = id
    }

    var status: String = ""
        set(v) {
            field = v
            label.text = v.ifEmpty { title }
        }

    /** Put (or move) the overlay on [activity]'s window, on top of everything in it. */
    fun attachTo(activity: Activity) {
        val decor = activity.window.decorView as ViewGroup
        if (root.parent === decor) {
            root.bringToFront()
            return
        }
        (root.parent as? ViewGroup)?.removeView(root)
        decor.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.bringToFront()
    }

    fun detach() {
        (root.parent as? ViewGroup)?.removeView(root)
    }

    /** What the library's frames depend on. */
    fun isVisible(resumed: Activity?): Boolean {
        val decor = resumed?.window?.decorView ?: return false
        if (root.parent !== decor || !root.isAttachedToWindow || !pill.isShown || pill.alpha < 0.9f || root.alpha < 0.9f) return false
        if (pill.width < dp(40) || pill.height < dp(20)) return false
        val r = Rect()
        if (!pill.getGlobalVisibleRect(r)) return false
        if (r.width() < pill.width || r.height() < pill.height) return false
        // Nothing of the app's is drawn over it: the overlay is the decor view's last child.
        val parent = root.parent as ViewGroup
        return parent.getChildAt(parent.childCount - 1) === root
    }

    /** Whether a window point is on the pill (with a margin). */
    fun onPill(x: Float, y: Float): Boolean {
        if (!pill.isAttachedToWindow) return false
        val at = IntArray(2)
        pill.getLocationInWindow(at)
        val m = dp(8)
        return x >= at[0] - m && x < at[0] + pill.width + m && y >= at[1] - m && y < at[1] + pill.height + m
    }

    fun pointer(x: Float, y: Float) = marks.pointer(x, y)

    fun ring(x: Float, y: Float, text: String?) = marks.ring(x, y, text)

    /** The agent's pointer and "tap here" rings, in window pixels. */
    private class MarkLayer(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private var pointerAt: Pair<Float, Float>? = null
        private var pointerUntil = 0L
        private val rings = mutableListOf<Triple<Float, Float, String?>>()
        private var ringsUntil = 0L
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 0, 122, 255) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2 * d
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 149, 0)
            style = Paint.Style.STROKE
            strokeWidth = 4 * d
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 149, 0)
            textSize = 15 * d
            isFakeBoldText = true
        }

        fun pointer(x: Float, y: Float) {
            pointerAt = x to y
            pointerUntil = System.currentTimeMillis() + 2500
            invalidate()
            postDelayed({ invalidate() }, 2600)
        }

        fun ring(x: Float, y: Float, text: String?) {
            rings += Triple(x, y, text)
            ringsUntil = System.currentTimeMillis() + 4000
            invalidate()
            postDelayed({ invalidate() }, 4100)
        }

        override fun onDraw(canvas: Canvas) {
            val now = System.currentTimeMillis()
            if (now > ringsUntil) rings.clear()
            for ((x, y, t) in rings) {
                canvas.drawCircle(x, y, 28 * d, ringPaint)
                t?.let { canvas.drawText(it, x - textPaint.measureText(it) / 2, y + 52 * d, textPaint) }
            }
            val p = pointerAt
            if (p != null && now <= pointerUntil) {
                canvas.drawCircle(p.first, p.second, 9 * d, dot)
                canvas.drawCircle(p.first, p.second, 9 * d, edge)
            }
        }
    }
}
