package com.lovja.prompter

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.lovja.prompter.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), ControlPanel.Actions {

    private lateinit var b: ActivityMainBinding
    private val engine = PrompterEngine()
    private lateinit var rear: RearDisplayHelper

    /** Büyük ekrandaki yedek panel (dış ekran yokken). */
    private lateinit var innerPanel: ControlPanel
    /** Küçük (dış) ekrandaki panel. */
    private var outerPanel: ControlPanel? = null

    private var rearState = RearDisplayHelper.State.CHECKING
    private var rearDetail = ""
    private var editing = false
    private var extraMessage: String? = null

    private var preview: Preview? = null
    private var previewTarget: ControlPanel? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordStart = 0L
    private var countingDown = false
    private var deviceRotation = Surface.ROTATION_0

    private val prefs by lazy { getSharedPreferences("prompter", Context.MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) startCamera()
            else toast("Kamera izni olmadan kayıt yapılamaz.")
        }

    private val timerTick = object : Runnable {
        override fun run() {
            if (recording == null) return
            val s = (SystemClock.elapsedRealtime() - recordStart) / 1000
            val t = String.format("● %02d:%02d", s / 60, s % 60)
            panels().forEach { it.setTimer(t) }
            b.root.postDelayed(this, 500)
        }
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val r = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (r != deviceRotation) {
                    deviceRotation = r
                    // Kayıt sürerken videonun yönü sabit kalır.
                    if (recording == null && !countingDown) {
                        videoCapture?.targetRotation = r
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine.setText(prefs.getString("script", null) ?: getString(R.string.default_script))
        engine.wordsPerSecond = prefs.getFloat("wps", 2.2f)
        engine.textSizeSp = prefs.getFloat("size", 34f)

        innerPanel = ControlPanel(this, engine, this)
        b.panelHost.addView(innerPanel.root)

        rear = RearDisplayHelper(
            activity = this,
            createContent = { ctx ->
                ControlPanel(ctx, engine, this).also {
                    outerPanel = it
                    it.setAngle(prefs.getInt("outerAngle", 0))
                    syncPanels()
                }.root
            },
            onStateChanged = { state, detail ->
                rearState = state
                rearDetail = detail
                if (state != RearDisplayHelper.State.ACTIVE) outerPanel = null
                refresh()
            },
        )
        rear.startObserving()

        setupEditor()
        refresh()

        if (hasPermission(Manifest.permission.CAMERA)) startCamera()
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
    }

    private fun panels(): List<ControlPanel> = listOfNotNull(innerPanel, outerPanel)

    private fun outerActive() = rearState == RearDisplayHelper.State.ACTIVE && outerPanel != null

    /** Büyük ekranın karartılması, durum yazısı ve kameranın hangi ekranda göründüğü. */
    private fun refresh() {
        val blackout = outerActive() && !editing
        b.blackout.visibility = if (blackout) View.VISIBLE else View.GONE
        b.editorPanel.visibility = if (editing) View.VISIBLE else View.GONE
        window.attributes = window.attributes.apply {
            screenBrightness = if (blackout) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        val status = extraMessage ?: when (rearState) {
            RearDisplayHelper.State.CHECKING -> "Küçük ekran kontrol ediliyor…"
            RearDisplayHelper.State.ACTIVE -> "Hazır. Kendini kadraja al, “Başla”ya bas."
            RearDisplayHelper.State.AVAILABLE -> "Küçük ekran hazır, açılıyor…"
            RearDisplayHelper.State.UNAVAILABLE -> "Küçük ekran şu an kullanılamıyor. Telefonu tamamen aç."
            RearDisplayHelper.State.UNSUPPORTED -> "Bu telefon küçük ekrana görüntü göndermeyi desteklemiyor görünüyor."
        }
        panels().forEach { it.setStatus(status) }
        attachPreview()
    }

    private fun syncPanels() {
        refresh()
        val p = outerPanel ?: return
        when {
            recording != null -> p.showRecording()
            else -> p.showSetup()
        }
    }

    // ---------- Panel düğmeleri ----------

    override fun onRecord() {
        if (recording != null || countingDown) return
        if (videoCapture == null) { toast("Kamera hazır değil."); return }
        engine.pause()
        engine.rewind()
        countingDown = true
        videoCapture?.targetRotation = deviceRotation
        countdown(3)
    }

    private fun countdown(n: Int) {
        if (!countingDown) return
        if (n == 0) {
            countingDown = false
            startRecording()
            return
        }
        panels().forEach { it.showCountdown(n) }
        b.root.postDelayed({ countdown(n - 1) }, 1000)
    }

    override fun onStopRecording() {
        if (countingDown) {
            countingDown = false
            panels().forEach { it.showSetup() }
            return
        }
        engine.pause()
        recording?.stop()
    }

    override fun onPaste() {
        val txt = try {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (t: Throwable) { null }
        if (txt.isNullOrBlank()) {
            flash("Pano boş ya da okunamadı. “Düzenle”ye basıp büyük ekranda yapıştır.")
        } else {
            setScript(txt.trim())
            flash("Metin yapıştırıldı (${engine.wordCount()} kelime).")
        }
    }

    override fun onEdit() {
        editing = true
        b.scriptEdit.setText(engine.text)
        flash("Metni büyük ekranda düzenle, bitince “Tamam”a bas.", 6000)
        refresh()
    }

    override fun onRotate() {
        val p = outerPanel ?: return
        val a = (prefs.getInt("outerAngle", 0) + 90) % 360
        prefs.edit().putInt("outerAngle", a).apply()
        p.setAngle(a)
    }

    override fun onSettingsChanged() {
        prefs.edit().putFloat("wps", engine.wordsPerSecond).putFloat("size", engine.textSizeSp).apply()
    }

    private fun setupEditor() {
        b.btnEditorPaste.setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val txt = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (txt.isNullOrBlank()) toast("Panoda metin yok.") else b.scriptEdit.setText(txt)
        }
        b.btnClear.setOnClickListener { b.scriptEdit.setText("") }
        b.btnDone.setOnClickListener {
            setScript(b.scriptEdit.text.toString().trim())
            editing = false
            hideKeyboard()
            extraMessage = null
            refresh()
        }
    }

    private fun setScript(t: String) {
        engine.pause()
        engine.setText(t)
        prefs.edit().putString("script", t).apply()
    }

    /** Durum satırında birkaç saniye bir mesaj gösterir. */
    private fun flash(msg: String, ms: Long = 4000) {
        extraMessage = msg
        refresh()
        b.root.postDelayed({
            if (extraMessage == msg) { extraMessage = null; refresh() }
        }, ms)
    }

    // ---------- Kamera ----------

    /** Kamera görüntüsü, kontrollerin olduğu ekranda gösterilir. */
    private fun attachPreview() {
        val target = if (outerActive()) outerPanel!! else innerPanel
        val p = preview ?: return
        // Aynı ekrana tekrar bağlamak kamerayı bir anlığına durdurur; sadece değişince bağla.
        if (previewTarget === target) return
        previewTarget = target
        p.setSurfaceProvider(target.previewView.surfaceProvider)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val p = Preview.Builder().build()
            // Full HD: 4K bazı cihazlarda önizleme ile birlikte veri üretmeyebiliyor (hata 8).
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))
                )
                .build()
            val capture = VideoCapture.withOutput(recorder)
            capture.targetRotation = deviceRotation
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, capture)
                preview = p
                previewTarget = null
                videoCapture = capture
                attachPreview()
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                toast("Kamera açılamadı: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: run { toast("Kamera hazır değil."); panels().forEach { it.showSetup() }; return }
        val name = "LOVJA_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LOVJA Prompter")
        }
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        var pending = capture.output.prepareRecording(this, options)
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        panels().forEach { it.showRecording() }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordStart = SystemClock.elapsedRealtime()
                    b.root.post(timerTick)
                    engine.play()
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    engine.pause()
                    engine.rewind()
                    panels().forEach { it.showSetup() }
                    if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        Log.e(TAG, "record error ${event.error}", event.cause)
                        flash("Kayıt sorunu: ${errorText(event.error)} (kod ${event.error}). ${event.cause?.message ?: ""}", 12000)
                    } else {
                        flash("Video kaydedildi: Galeri › LOVJA Prompter", 6000)
                    }
                }
            }
        }
    }

    private fun errorText(code: Int) = when (code) {
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "kameradan görüntü gelmedi"
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "telefonda yer kalmadı"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "kamera kapandı"
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "video işlenemedi"
        VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "kaydedici hatası"
        else -> "bilinmeyen hata"
    }

    // ---------- Yardımcılar ----------

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(b.scriptEdit.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object { private const val TAG = "LovjaPrompter" }
}
