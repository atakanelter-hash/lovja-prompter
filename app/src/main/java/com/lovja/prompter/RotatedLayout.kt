package com.lovja.prompter

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * Tek bir içeriği 0/90/180/270 derece döndürerek gösterir.
 * Dış ekran telefonla birlikte kendiliğinden dönmezse yazıyı düz tutmak için.
 */
class RotatedLayout(context: Context) : FrameLayout(context) {

    var angle: Int = 0
        set(value) {
            val v = ((value % 360) + 360) % 360
            if (v != field) {
                field = v
                requestLayout()
            }
        }

    private fun swapped() = angle == 90 || angle == 270

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = if (swapped()) h else w
        val ch = if (swapped()) w else h
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            val c: View = getChildAt(i)
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            val left = (w - cw) / 2
            val top = (h - ch) / 2
            c.layout(left, top, left + cw, top + ch)
            c.pivotX = cw / 2f
            c.pivotY = ch / 2f
            c.rotation = angle.toFloat()
        }
    }
}
