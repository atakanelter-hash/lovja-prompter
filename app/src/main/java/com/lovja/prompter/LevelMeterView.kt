package com.lovja.prompter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Mikrofon ses seviyesi çubuğu: yeşil iyi, sarı yüksek, kırmızı fazla. */
class LevelMeterView(context: Context) : View(context) {
    private val bg = Paint().apply { color = Color.parseColor("#333333") }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0f
    private var shown = 0f

    fun setLevel(v: Float) {
        level = v.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        shown = if (level > shown) level else shown * 0.85f + level * 0.15f
        val r = height / 2f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, bg)
        // Logaritmik gösterim: konuşma sesi çubuğun ortalarına gelsin.
        val db = if (shown <= 0.0001f) -60f else 20f * kotlin.math.log10(shown)
        val frac = ((db + 60f) / 60f).coerceIn(0f, 1f)
        fg.color = when {
            frac > 0.95f -> Color.parseColor("#E53935")
            frac > 0.8f -> Color.parseColor("#FDD835")
            else -> Color.parseColor("#43A047")
        }
        canvas.drawRoundRect(RectF(0f, 0f, width * frac, height.toFloat()), r, r, fg)
        if (shown > 0.001f) postInvalidateOnAnimation()
    }
}
