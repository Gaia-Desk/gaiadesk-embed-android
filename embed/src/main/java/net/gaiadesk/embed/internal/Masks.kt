package net.gaiadesk.embed.internal

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What is masked, measured on the main thread at capture time and pushed with the frame (so a
 * mask moves with the picture it covers):
 *
 *  - the views the app registered (`setMaskedViews`), wherever they are now;
 *  - every password field, found by itself: a `TextView` (EditText) whose inputType is a
 *    password variation is masked whether or not the app registered it.
 *
 * Compose text fields are not Views: mask them with `setMaskedRects`.
 */
internal class Masks {
    private val views = CopyOnWriteArrayList<WeakReference<View>>()

    fun setViews(list: List<View>) {
        views.clear()
        list.forEach { views.add(WeakReference(it)) }
    }

    /** The masked rects now (window pixels: x, y, w, h). Main thread. */
    fun measure(root: View?): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (ref in views) ref.get()?.let { v -> rectOf(v)?.let(out::add) }
        if (root != null) secureFields(root) { v -> rectOf(v)?.let(out::add) }
        return out
    }

    companion object {
        // InputType's constants, spelled out so the decision is testable on the JVM.
        private const val TYPE_MASK_CLASS = 0x0000000f
        private const val TYPE_MASK_VARIATION = 0x00000ff0
        private const val TYPE_CLASS_TEXT = 1
        private const val TYPE_CLASS_NUMBER = 2
        private const val TEXT_PASSWORD = 0x80
        private const val TEXT_VISIBLE_PASSWORD = 0x90
        private const val TEXT_WEB_PASSWORD = 0xe0
        private const val NUMBER_PASSWORD = 0x10

        /** An inputType that holds a secret. Pure. */
        fun isSecureInputType(t: Int): Boolean {
            val cls = t and TYPE_MASK_CLASS
            val v = t and TYPE_MASK_VARIATION
            return (cls == TYPE_CLASS_TEXT && (v == TEXT_PASSWORD || v == TEXT_VISIBLE_PASSWORD || v == TEXT_WEB_PASSWORD)) ||
                (cls == TYPE_CLASS_NUMBER && v == NUMBER_PASSWORD)
        }

        /** Whether two x, y, w, h rects overlap. Pure. */
        fun overlaps(a: DoubleArray, b: DoubleArray): Boolean =
            a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]

        fun isSecure(v: View?): Boolean = (v as? TextView)?.let { isSecureInputType(it.inputType) } ?: false

        fun rectOf(v: View): DoubleArray? {
            if (!v.isAttachedToWindow || !v.isShown || v.width <= 0 || v.height <= 0) return null
            val at = IntArray(2)
            v.getLocationInWindow(at)
            return doubleArrayOf(at[0].toDouble(), at[1].toDouble(), v.width.toDouble(), v.height.toDouble())
        }

        private fun secureFields(v: View, found: (View) -> Unit) {
            if (isSecure(v)) found(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) secureFields(v.getChildAt(i), found)
        }
    }
}
