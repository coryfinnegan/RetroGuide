package com.ersatz.retroguide

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The raised plate every surface of the guide is made of.
 *
 * Taken from the Prevue Channel grid of 1993-99: one flat fill, a bright line
 * along the top and left edges and a dark one along the bottom and right, so
 * each box reads as embossed. No gradients and no rounded corners - those
 * arrived with the 2000s guides and read as modern.
 */
object Emboss {
    /** [c] moved [t] of the way toward [to]. */
    private fun mix(c: Int, to: Int, t: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xFF) + ((((to shr s) and 0xFF) - ((c shr s) and 0xFF)) * t)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The highlight edge: most of the way to white, as on the Prevue grid. */
    fun hi(fill: Int) = mix(fill, 0xFFFFFFFF.toInt(), 0.78f)

    /** The shadow edge. */
    fun lo(fill: Int) = mix(fill, 0xFF000000.toInt(), 0.6f)

    private val paint = Paint()

    /** Draw a plate into [l, t, r, b] with edges [w] pixels thick. */
    fun draw(c: Canvas, l: Float, t: Float, r: Float, b: Float, fill: Int, w: Float) {
        paint.color = fill
        c.drawRect(l, t, r, b, paint)
        paint.color = lo(fill)
        c.drawRect(l, b - w, r, b, paint)
        c.drawRect(r - w, t, r, b, paint)
        paint.color = hi(fill)
        c.drawRect(l, t, r - w, t + w, paint)
        c.drawRect(l, t, l + w, b - w, paint)
    }

    /** The same plate as a view background. */
    class Plate(private val fill: Int, private val w: Float) : Drawable() {
        override fun draw(canvas: Canvas) {
            val r = bounds
            draw(canvas, r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), fill, w)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
