package com.lovja.prompter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.camera.view.PreviewView
import com.lovja.prompter.databinding.PanelControlsBinding

/**
 * Tüm kontrollerin bulunduğu arayüz. Normalde küçük (dış) ekranda gösterilir;
 * dış ekran kullanılamıyorsa büyük ekranda.
 */
class ControlPanel(
    context: Context,
    private val engine: PrompterEngine,
    private val actions: Actions,
) {
    interface Actions {
        fun onRecord()
        fun onStopRecording()
        fun onPaste()
        fun onEdit()
        fun onRotate()
        fun onSettingsChanged()
    }

    private val themed = ContextThemeWrapper(context, R.style.Theme_LovjaPrompter)
    val root = RotatedLayout(themed)
    private val b = PanelControlsBinding.inflate(LayoutInflater.from(themed), root, true)
    private val miniPrompter = PrompterView(themed, engine)
    private val fullPrompter = PrompterView(themed, engine)
    private val engineListener: () -> Unit = { updateSpeed() }

    val previewView: PreviewView get() = b.preview

    init {
        b.miniPrompterHolder.addView(miniPrompter)
        b.prompterHolder.addView(fullPrompter)
        miniPrompter.anchor = 0.15f
        fullPrompter.anchor = 0.30f
        root.setBackgroundColor(0xFF000000.toInt())
        root.keepScreenOn = true

        b.btnSlower.setOnClickListener { engine.wordsPerSecond -= 0.2f; actions.onSettingsChanged() }
        b.btnFaster.setOnClickListener { engine.wordsPerSecond += 0.2f; actions.onSettingsChanged() }
        b.btnSmaller.setOnClickListener { engine.textSizeSp -= 3f; actions.onSettingsChanged() }
        b.btnBigger.setOnClickListener { engine.textSizeSp += 3f; actions.onSettingsChanged() }
        b.btnPaste.setOnClickListener { actions.onPaste() }
        b.btnEdit.setOnClickListener { actions.onEdit() }
        b.btnRotate.setOnClickListener { actions.onRotate() }
        b.btnRecord.setOnClickListener { actions.onRecord() }
        b.btnStop.setOnClickListener { actions.onStopRecording() }

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { engine.addListener(engineListener) }
            override fun onViewDetachedFromWindow(v: View) { engine.removeListener(engineListener) }
        })
        updateSpeed()
    }

    private fun updateSpeed() {
        b.speedText.text = "${(engine.wordsPerSecond * 60).toInt()} kel/dk"
    }

    fun setStatus(text: String) {
        b.statusText.text = text
    }

    fun setAngle(deg: Int) {
        root.angle = deg
    }

    /** Hazırlık → geri sayım → kayıt görünümleri. */
    fun showSetup() {
        b.setupPanel.visibility = View.VISIBLE
        b.prompterHolder.visibility = View.GONE
        b.recordBar.visibility = View.GONE
        b.countdownText.visibility = View.GONE
    }

    fun showCountdown(n: Int) {
        b.setupPanel.visibility = View.GONE
        b.prompterHolder.visibility = View.VISIBLE
        b.recordBar.visibility = View.VISIBLE
        b.timerText.text = ""
        b.countdownText.visibility = View.VISIBLE
        b.countdownText.text = n.toString()
    }

    fun showRecording() {
        b.setupPanel.visibility = View.GONE
        b.prompterHolder.visibility = View.VISIBLE
        b.recordBar.visibility = View.VISIBLE
        b.countdownText.visibility = View.GONE
    }

    fun setTimer(text: String) {
        b.timerText.text = text
    }
}
