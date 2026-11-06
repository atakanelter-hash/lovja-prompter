package com.lovja.prompter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View

/**
 * Metni altyazı gibi gösteren ekran. Okunan satır beyaz ve vurgulu,
 * diğer satırlar soluk. Konumu [PrompterEngine]'den alır.
 */
@SuppressLint("ViewConstructor")
class PrompterView(context: Context, private val engine: PrompterEngine) : View(context) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33C8A96A") }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C8A96A") }
    private val dimColor = Color.parseColor("#7A7A7A")
    private val readColor = Color.parseColor("#4A4A4A")
    private val activeColor = Color.WHITE

    private var layout: StaticLayout? = null
    private var layoutKey = ""

    /** Okunan satırın ekranda durduğu yükseklik (0 = en üst, 1 = en alt). */
    var anchor = 0.30f

    private val listener: () -> Unit = { postInvalidateOnAnimation() }

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        engine.addListener(listener)
    }

    override fun onDetachedFromWindow() {
        engine.removeListener(listener)
        super.onDetachedFromWindow()
    }

    private fun padding() = (width * 0.06f).toInt()

    private fun ensureLayout(): StaticLayout? {
        val w = width - 2 * padding()
        if (w <= 0) return null
        val key = "${engine.text.hashCode()}|$w|${engine.textSizeSp}"
        if (key != layoutKey || layout == null) {
            textPaint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, engine.textSizeSp, resources.displayMetrics
            )
            textPaint.isFakeBoldText = true
            val t = engine.text.ifBlank { "Metin boş. İç ekranda “Metin” düğmesine basıp yazını yapıştır." }
            layout = StaticLayout.Builder.obtain(t, 0, t.length, textPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.25f)
                .setIncludePad(false)
                .build()
            layoutKey = key
        }
        return layout
    }

    /** Ekranda gösterilen kaydırma; hedefe yumuşakça yaklaşır. */
    private var shownScroll = Float.NaN

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = ensureLayout() ?: return
        val n = engine.wordCount()
        val activeLine = if (n == 0) 0 else
            l.getLineForOffset(engine.wordStarts[engine.position.toInt().coerceIn(0, n - 1)])

        val lineTop = l.getLineTop(activeLine).toFloat()
        val lineH = (l.getLineBottom(activeLine) - l.getLineTop(activeLine)).toFloat()
        val anchorY = height * anchor
        val target = lineTop - anchorY
        if (shownScroll.isNaN() || kotlin.math.abs(target - shownScroll) > height) shownScroll = target
        shownScroll += (target - shownScroll) * 0.18f
        if (kotlin.math.abs(target - shownScroll) > 0.5f) postInvalidateOnAnimation() else shownScroll = target

        val pad = padding().toFloat()

        // Vurgu bandı (sabit yerde durur, satırlar içine kayar)
        val extra = lineH * 0.12f
        canvas.drawRoundRect(RectF(pad * 0.4f, anchorY - extra, width - pad * 0.4f, anchorY + lineH + extra), 24f, 24f, barPaint)
        canvas.drawRect(0f, anchorY - extra, pad * 0.25f, anchorY + lineH + extra, markerPaint)

        canvas.save()
        canvas.translate(pad, -shownScroll)
        val first = l.getLineForVertical((shownScroll - lineH).toInt().coerceAtLeast(0))
        val last = l.getLineForVertical((shownScroll + height + lineH).toInt())
        for (line in first..last) {
            textPaint.color = when {
                line < activeLine -> readColor
                line == activeLine -> activeColor
                else -> dimColor
            }
            canvas.save()
            canvas.clipRect(0, l.getLineTop(line), l.width, l.getLineBottom(line))
            l.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
