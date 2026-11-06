package com.lovja.prompter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.camera.view.PreviewView

/**
 * Küçük (dış) ekranın tüm arayüzü:
 *  1) Ayarlar ekranı (biçim, kalite, mikrofon, yazı…)
 *  2) Kayıt ekranı: kendi görüntün + akan yazı, sade düğmeler.
 * Dış ekran yoksa aynı arayüz büyük ekranda gösterilir.
 */
class ControlPanel(
    context: Context,
    private val engine: PrompterEngine,
    private val settings: Settings,
    private val actions: Actions,
) {
    interface Actions {
        fun onRecord()
        fun onStopRecording()
        fun onPaste()
        fun onEdit()
        fun onRotate()
        fun onSettingsChanged()
        fun onFormatChanged()
        fun onMicSelected(id: Int)
        fun onMicTest()
        fun onPanelModeChanged()
    }

    private val ctx = ContextThemeWrapper(context, R.style.Theme_LovjaPrompter)
    private val gold = Color.parseColor("#C8A96A")

    val root = RotatedLayout(ctx)
    private val content = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

    // ----- Kayıt ekranı -----
    private val stage = FrameLayout(ctx)
    private val previewBox = FrameLayout(ctx).apply { setBackgroundColor(Color.parseColor("#111111")) }
    private val previewRot = RotatedLayout(ctx)
    val previewView = PreviewView(ctx).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val prompter = PrompterView(ctx, engine)
    private val countdown = text("", 96f, gold).apply { typeface = Typeface.DEFAULT_BOLD; visibility = View.GONE }
    private val timer = text("", 13f, Color.parseColor("#FF5252"))
    private val recordBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(timer, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        addView(smallBtn("■ Bitir") { actions.onStopRecording() })
    }
    private val btnSwap = smallBtn("⇄") {
        settings.previewRight = !settings.previewRight
        layoutStage()
    }
    private val idleBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(smallBtn("‹ Ayarlar") { showSettings() })
        addView(Button(ctx).apply {
            text = "● Başla"
            style(this, 17f)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E53935"))
            setPadding(dp(26), 0, dp(26), 0)
            setOnClickListener { actions.onRecord() }
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(6); marginEnd = dp(6) })
        addView(smallBtn("↻") { actions.onRotate() })
        addView(btnSwap)
    }

    // ----- Ayarlar ekranı -----
    private val settingsScroll = ScrollView(ctx).apply { setBackgroundColor(Color.BLACK) }
    private val statusText = text("", 13f, Color.WHITE)
    private val btnVertical = toggleBtn("Dikey 9:16 (Reels)") { settings.vertical = true; actions.onFormatChanged(); refresh() }
    private val btnHorizontal = toggleBtn("Yatay 16:9") { settings.vertical = false; actions.onFormatChanged(); refresh() }
    private val btnFhd = toggleBtn("Full HD") { settings.uhd = false; actions.onFormatChanged(); refresh() }
    private val btnUhd = toggleBtn("4K") { settings.uhd = true; actions.onFormatChanged(); refresh() }
    private val micList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val meter = LevelMeterView(ctx)
    private val testState = text("", 12f, Color.parseColor("#AAAAAA"))
    private val offsetText = text("", 13f, Color.WHITE)
    private val wordsText = text("", 12f, Color.parseColor("#AAAAAA"))
    private val speedText = text("", 13f, Color.WHITE)
    private val sizeText = text("", 13f, Color.WHITE)
    private val btnMirror = toggleBtn("Ayna görüntü") { settings.mirror = !settings.mirror; refresh() }
    private var selectedMicId = -1

    var inSettings = true
        private set

    init {
        // Kayıt ekranı
        previewRot.addView(previewView)
        previewBox.addView(previewRot, FrameLayout.LayoutParams(-1, -1))
        stage.addView(previewBox, FrameLayout.LayoutParams(0, 0))
        stage.addView(prompter, FrameLayout.LayoutParams(0, 0))
        stage.addView(countdown, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        stage.addView(recordBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        stage.addView(idleBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(8) })
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) stage.post { layoutStage() }
        }

        // Ayarlar ekranı
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(20))
        }
        col.addView(text("LOVJA Prompter · Ayarlar", 18f, gold).apply { typeface = Typeface.DEFAULT_BOLD })
        col.addView(statusText, lp().apply { topMargin = dp(6) })

        col.addView(section("Video biçimi"))
        col.addView(row(btnVertical, btnHorizontal))
        col.addView(section("Kalite"))
        col.addView(row(btnFhd, btnUhd))
        col.addView(text("Telefonu yan tutup Reels çekeceksen 4K seç; görüntü kırpıldığı için netlik korunur.", 11f, Color.parseColor("#888888")))

        col.addView(section("Mikrofon"))
        col.addView(micList)
        col.addView(meter, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(8) })
        col.addView(row(smallBtn("Ses testi (5 sn)") { actions.onMicTest() }, testState))
        col.addView(text("Ses–görüntü uyumu (sadece yaka mikrofonunda, gerekirse):", 11f, Color.parseColor("#888888")), lp().apply { topMargin = dp(6) })
        col.addView(row(
            smallBtn("−") { settings.audioOffsetMs -= 20; refresh() },
            offsetText,
            smallBtn("+") { settings.audioOffsetMs += 20; refresh() },
        ))

        col.addView(section("Yazı"))
        col.addView(wordsText)
        col.addView(row(smallBtn("Panodan yapıştır") { actions.onPaste() }, smallBtn("Düzenle (büyük ekranda)") { actions.onEdit() }))
        col.addView(row(text("Hız", 13f, Color.WHITE),
            smallBtn("−") { engine.wordsPerSecond -= 0.2f; actions.onSettingsChanged(); refresh() },
            speedText,
            smallBtn("+") { engine.wordsPerSecond += 0.2f; actions.onSettingsChanged(); refresh() }))
        col.addView(row(text("Boyut", 13f, Color.WHITE),
            smallBtn("A−") { engine.textSizeSp -= 3f; actions.onSettingsChanged(); refresh() },
            sizeText,
            smallBtn("A+") { engine.textSizeSp += 3f; actions.onSettingsChanged(); refresh() }))

        col.addView(section("Görünüm"))
        col.addView(row(btnMirror, smallBtn("Ekranı döndür ↻") { actions.onRotate() }))

        col.addView(Button(ctx).apply {
            text = "Kayda geç  ›"
            style(this, 18f)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E53935"))
            setOnClickListener { showStage() }
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(18) })
        settingsScroll.addView(col)

        content.addView(stage, FrameLayout.LayoutParams(-1, -1))
        content.addView(settingsScroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(content)
        root.keepScreenOn = true
        refresh()
    }

    // ---------- Görünümler ----------

    fun showSettings() {
        inSettings = true
        settingsScroll.visibility = View.VISIBLE
        actions.onPanelModeChanged()
    }

    fun showStage() {
        inSettings = false
        settingsScroll.visibility = View.GONE
        showIdle()
        layoutStage()
        actions.onPanelModeChanged()
    }

    fun showIdle() {
        countdown.visibility = View.GONE
        recordBar.visibility = View.GONE
        if (!inSettings) idleBar.visibility = View.VISIBLE
    }

    fun showCountdown(n: Int) {
        if (inSettings) { inSettings = false; settingsScroll.visibility = View.GONE; layoutStage() }
        idleBar.visibility = View.GONE
        recordBar.visibility = View.VISIBLE
        timer.text = ""
        countdown.visibility = View.VISIBLE
        countdown.text = n.toString()
    }

    fun showRecording() {
        if (inSettings) { inSettings = false; settingsScroll.visibility = View.GONE; layoutStage() }
        idleBar.visibility = View.GONE
        countdown.visibility = View.GONE
        recordBar.visibility = View.VISIBLE
    }

    fun setTimer(t: String) { timer.text = t }
    fun setStatus(t: String) { statusText.text = t }
    fun setLevel(v: Float) { meter.setLevel(v) }
    fun setTestState(t: String) { testState.text = t }

    fun setAngle(deg: Int) {
        root.angle = deg
        previewRot.angle = -deg
        stage.post { layoutStage() }
    }

    fun setMics(mics: List<AudioEngine.Mic>, selectedId: Int, activeLabel: String?) {
        selectedMicId = selectedId
        micList.removeAllViews()
        mics.forEach { m ->
            val label = if (m.id == -1 && activeLabel != null) "${m.label}\n→ şu an: $activeLabel" else m.label
            val b = toggleBtn(label) { actions.onMicSelected(m.id) }
            b.isSelected = m.id == selectedId
            tint(b)
            micList.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }

    /** Ayar değerlerini ekrana yansıtır. */
    fun refresh() {
        btnVertical.isSelected = settings.vertical
        btnHorizontal.isSelected = !settings.vertical
        btnFhd.isSelected = !settings.uhd
        btnUhd.isSelected = settings.uhd
        btnMirror.isSelected = settings.mirror
        listOf(btnVertical, btnHorizontal, btnFhd, btnUhd, btnMirror).forEach { tint(it) }
        btnMirror.text = if (settings.mirror) "Ayna görüntü: Açık" else "Ayna görüntü: Kapalı"
        offsetText.text = "${settings.audioOffsetMs} ms"
        wordsText.text = "${engine.wordCount()} kelime. Baştan: " + engine.text.take(60).replace('\n', ' ') + "…"
        speedText.text = "${(engine.wordsPerSecond * 60).toInt()} kel/dk"
        sizeText.text = "${engine.textSizeSp.toInt()}"
        previewBox.scaleX = if (settings.mirror) -1f else 1f
        layoutStage()
    }

    /**
     * Kayıt ekranı düzeni:
     *  - Dikey video + yan tutulmuş ekran: görüntün bir yanda, yazı yanındaki boşlukta.
     *  - Diğer durumlarda: görüntün ekranı kaplar, yazı üstünden yarı saydam akar.
     */
    private fun layoutStage() {
        val w = stage.width
        val h = stage.height
        if (w == 0 || h == 0) return
        val aspect = if (settings.vertical) 9f / 16f else 16f / 9f
        val pLp = previewBox.layoutParams as FrameLayout.LayoutParams
        val tLp = prompter.layoutParams as FrameLayout.LayoutParams

        if (settings.vertical && w > h) {
            val pw = minOf((h * aspect).toInt(), (w * 0.55f).toInt())
            val ph = (pw / aspect).toInt()
            pLp.width = pw; pLp.height = ph
            pLp.gravity = (if (settings.previewRight) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            tLp.width = w - pw; tLp.height = h
            tLp.gravity = if (settings.previewRight) Gravity.START else Gravity.END
            prompter.overlay = false
            prompter.anchor = 0.25f
            btnSwap.visibility = View.VISIBLE
        } else {
            val pw: Int; val ph: Int
            if (w.toFloat() / h > aspect) { ph = h; pw = (h * aspect).toInt() } else { pw = w; ph = (w / aspect).toInt() }
            pLp.width = pw; pLp.height = ph
            pLp.gravity = Gravity.CENTER
            tLp.width = w; tLp.height = (h * 0.42f).toInt()
            tLp.gravity = Gravity.TOP
            prompter.overlay = true
            prompter.anchor = 0.35f
            btnSwap.visibility = View.GONE
        }
        previewBox.layoutParams = pLp
        prompter.layoutParams = tLp
    }

    // ---------- Küçük yardımcılar ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    private fun lp() = LinearLayout.LayoutParams(-1, -2)

    private fun text(t: String, sp: Float, color: Int) = TextView(ctx).apply {
        text = t; setTextColor(color); textSize = sp
    }

    private fun section(t: String) = text(t, 15f, gold).apply {
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = lp().apply { topMargin = dp(16) }
    }

    private fun style(b: Button, sp: Float) {
        b.setBackgroundResource(R.drawable.btn_bg)
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = sp
        b.minWidth = dp(44)
        b.minimumWidth = dp(44)
        b.setPadding(dp(12), 0, dp(12), 0)
    }

    private fun smallBtn(t: String, onClick: () -> Unit) = Button(ctx).apply {
        text = t
        style(this, 13f)
        setOnClickListener { onClick() }
    }

    private fun toggleBtn(t: String, onClick: () -> Unit) = Button(ctx).apply {
        text = t
        style(this, 13f)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    private fun tint(b: Button) {
        b.backgroundTintList = ColorStateList.valueOf(if (b.isSelected) gold else Color.parseColor("#333333"))
        b.setTextColor(if (b.isSelected) Color.BLACK else Color.WHITE)
    }

    private fun row(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp().apply { topMargin = dp(6) }
        views.forEach { v ->
            val p = if (v is Button) LinearLayout.LayoutParams(-2, ViewGroup.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(-2, -2)
            p.marginEnd = dp(6)
            addView(v, p)
        }
    }
}
