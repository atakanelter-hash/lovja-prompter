package com.lovja.prompter

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.lovja.prompter.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity(), ControlPanel.Actions {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var audio: AudioEngine
    private val engine = PrompterEngine()
    private lateinit var rear: RearDisplayHelper

    /** Büyük ekrandaki yedek panel (dış ekran yokken). */
    private lateinit var innerPanel: ControlPanel
    /** Küçük (dış) ekrandaki panel. */
    private var outerPanel: ControlPanel? = null

    private var rearState = RearDisplayHelper.State.CHECKING
    private var editing = false
    private var extraMessage: String? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var previewTarget: ControlPanel? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var boundRotation = -1
    private var recording: Recording? = null
    private var recordStart = 0L
    private var countingDown = false
    private var busy = false
    private var deviceRotation = Surface.ROTATION_0

    // Harici mikrofonla kayıt
    private var extAudio = false
    private var audioBaseNanos = 0L
    private var videoStartNanos = Long.MAX_VALUE
    private var tmpVideo: File? = null
    private var tmpAudio: File? = null
    private var recordName = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) startCamera()
            else toast("Kamera izni olmadan kayıt yapılamaz.")
            if (result[Manifest.permission.RECORD_AUDIO] == true) restartAudio()
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

    private val rebindRunnable = Runnable { bindCamera() }

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
                    // Telefon döndü: kırpma ve video yönü yeniden ayarlansın (kayıt sırasında değil).
                    b.root.removeCallbacks(rebindRunnable)
                    b.root.postDelayed(rebindRunnable, 700)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = Settings(this)
        engine.setText(settings.script ?: getString(R.string.default_script))
        engine.wordsPerSecond = settings.wps
        engine.textSizeSp = settings.textSize

        audio = AudioEngine(this)
        audio.onLevel = { lvl -> panels().forEach { it.setLevel(lvl) } }
        audio.onTestState = { s -> panels().forEach { it.setTestState(s) } }
        audio.onDevicesChanged = {
            if (recording == null && !countingDown) restartAudio() else updateMicLists()
        }

        innerPanel = ControlPanel(this, engine, settings, this)
        b.panelHost.addView(innerPanel.root)

        rear = RearDisplayHelper(
            activity = this,
            createContent = { ctx ->
                ControlPanel(ctx, engine, settings, this).also {
                    outerPanel = it
                    it.setAngle(settings.outerAngle)
                    if (recording != null) it.showRecording()
                }.root
            },
            onStateChanged = { state, _ ->
                rearState = state
                if (state != RearDisplayHelper.State.ACTIVE) outerPanel = null
                refresh()
                updateMicLists()
            },
        )
        rear.startObserving()

        setupEditor()
        refresh()

        if (hasPermission(Manifest.permission.CAMERA)) startCamera()
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    override fun onStart() {
        super.onStart()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        restartAudio()
    }

    override fun onStop() {
        orientationListener.disable()
        if (recording == null && !countingDown) audio.stop()
        super.onStop()
    }

    override fun onDestroy() {
        audio.release()
        super.onDestroy()
    }

    private fun panels(): List<ControlPanel> = listOfNotNull(innerPanel, outerPanel)

    private fun outerActive() = rearState == RearDisplayHelper.State.ACTIVE && outerPanel != null

    /** Büyük ekranın karartılması, durum yazısı ve kamera görüntüsünün hangi ekranda olduğu. */
    private fun refresh() {
        // Büyük ekran sadece siyaha boyanır; parlaklığa dokunulmaz (küçük ekranı da karartıyordu).
        val blackout = outerActive() && !editing
        b.blackout.visibility = if (blackout) View.VISIBLE else View.GONE
        b.editorPanel.visibility = if (editing) View.VISIBLE else View.GONE

        val status = extraMessage ?: when (rearState) {
            RearDisplayHelper.State.CHECKING -> "Küçük ekran kontrol ediliyor…"
            RearDisplayHelper.State.ACTIVE -> "Küçük ekran hazır."
            RearDisplayHelper.State.AVAILABLE -> "Küçük ekran açılıyor…"
            RearDisplayHelper.State.UNAVAILABLE -> "Küçük ekran şu an kullanılamıyor. Telefonu tamamen aç."
            RearDisplayHelper.State.UNSUPPORTED -> "Bu telefon küçük ekrana görüntü göndermeyi desteklemiyor görünüyor."
        }
        panels().forEach { it.setStatus(status); it.refresh() }
        attachPreview()
    }

    // ---------- Mikrofon ----------

    private fun restartAudio() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return
        if (recording != null || countingDown) return
        audio.start(audio.resolve(settings.micId))
        updateMicLists()
    }

    private fun updateMicLists() {
        val mics = audio.mics()
        val active = audio.activeMic?.let { a -> mics.firstOrNull { it.id == a.id }?.label ?: a.label }
        panels().forEach { it.setMics(mics, settings.micId, active) }
    }

    override fun onMicSelected(id: Int) {
        if (recording != null) return
        settings.micId = id
        restartAudio()
    }

    override fun onMicTest() {
        audio.startTest(5)
    }

    // ---------- Panel düğmeleri ----------

    override fun onRecord() {
        if (recording != null || countingDown || busy) return
        if (videoCapture == null) { flash("Kamera hazır değil."); return }
        engine.pause()
        engine.rewind()
        countingDown = true
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
            panels().forEach { it.showIdle() }
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
    }

    override fun onRotate() {
        val a = (settings.outerAngle + 90) % 360
        settings.outerAngle = a
        outerPanel?.setAngle(a)
    }

    override fun onSettingsChanged() {
        settings.wps = engine.wordsPerSecond
        settings.textSize = engine.textSizeSp
    }

    override fun onFormatChanged() {
        bindCamera()
    }

    override fun onPanelModeChanged() {
        // Görünüm değişti (ayarlar ↔ kayıt ekranı); şimdilik ek iş yok.
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
        settings.script = t
        panels().forEach { it.refresh() }
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
        // Görüntünün yönü, gösterildiği ekranın yönüne göre.
        p.targetRotation = target.previewView.display?.rotation
            ?: if (target === innerPanel) (display?.rotation ?: Surface.ROTATION_0) else Surface.ROTATION_0
        p.setSurfaceProvider(target.previewView.surfaceProvider)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    /** Kamerayı seçili biçim (dikey/yatay), kalite ve telefonun duruşuna göre bağlar. */
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (recording != null || countingDown || busy) return
        val quality = if (settings.uhd) Quality.UHD else Quality.FHD
        val p = Preview.Builder().build()
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .build()
        val capture = VideoCapture.withOutput(recorder)
        capture.targetRotation = deviceRotation
        // Görüntü, seçilen biçime (9:16 ya da 16:9) telefonun tutuluşuna göre kırpılır.
        val ratio = if (settings.vertical) Rational(9, 16) else Rational(16, 9)
        val viewPort = ViewPort.Builder(ratio, deviceRotation).setScaleType(ViewPort.FILL_CENTER).build()
        val group = UseCaseGroup.Builder().addUseCase(p).addUseCase(capture).setViewPort(viewPort).build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
            preview = p
            previewTarget = null
            videoCapture = capture
            boundRotation = deviceRotation
            attachPreview()
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            flash("Kamera açılamadı: ${e.message}", 8000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: run { flash("Kamera hazır değil."); panels().forEach { it.showIdle() }; return }
        if (boundRotation != deviceRotation) bindCamera()
        val cap = videoCapture ?: capture
        recordName = "LOVJA_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(System.currentTimeMillis())

        val mic = audio.activeMic
        extAudio = mic?.external == true && hasPermission(Manifest.permission.RECORD_AUDIO)
        val pending = if (extAudio) {
            // Yaka mikrofonu: video sessiz kaydedilir, ses ayrı alınır, sonra birleştirilir.
            val v = File(cacheDir, "rec_video.mp4").also { it.delete() }
            val a = File(cacheDir, "rec_audio.m4a").also { it.delete() }
            tmpVideo = v; tmpAudio = a
            audioBaseNanos = System.nanoTime() - 1_000_000_000L
            videoStartNanos = Long.MAX_VALUE
            if (!audio.beginRecording(a, audioBaseNanos)) {
                extAudio = false
                flash("Yaka mikrofonu başlatılamadı, telefonun mikrofonu kullanılıyor.", 6000)
            }
            if (extAudio) cap.output.prepareRecording(this, FileOutputOptions.Builder(v).build()) else null
        } else null

        val finalPending = pending ?: run {
            // Telefonun kendi mikrofonu: kamera sesi kendisi kaydeder.
            audio.stop()
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, recordName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LOVJA Prompter")
            }
            val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values).build()
            var pr = cap.output.prepareRecording(this, options)
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) pr = pr.withAudioEnabled()
            pr
        }

        panels().forEach { it.showRecording() }
        recording = finalPending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordStart = SystemClock.elapsedRealtime()
                    b.root.post(timerTick)
                    engine.play()
                }
                is VideoRecordEvent.Status -> {
                    // Videonun ilk karesinin zamanını tahmin et (sesi buna hizalayacağız).
                    val d = event.recordingStats.recordedDurationNanos
                    if (extAudio && d > 0) videoStartNanos = minOf(videoStartNanos, System.nanoTime() - d)
                }
                is VideoRecordEvent.Finalize -> onFinalize(event)
            }
        }
    }

    private fun onFinalize(event: VideoRecordEvent.Finalize) {
        recording = null
        engine.pause()
        engine.rewind()
        panels().forEach { it.showIdle() }
        val failed = event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_NONE
        if (failed) {
            Log.e(TAG, "record error ${event.error}", event.cause)
            flash("Kayıt sorunu: ${errorText(event.error)} (kod ${event.error}). ${event.cause?.message ?: ""}", 12000)
        }
        if (!extAudio) {
            if (!failed) flash("Video kaydedildi: Galeri › LOVJA Prompter", 6000)
            restartAudio()
            if (boundRotation != deviceRotation) bindCamera()
            return
        }
        // Yaka mikrofonu: sesi ve görüntüyü birleştir.
        busy = true
        flash("Video hazırlanıyor, bekle…", 60_000)
        val v = tmpVideo!!; val a = tmpAudio!!
        val shiftUs = (if (videoStartNanos == Long.MAX_VALUE) 1_000_000_000L else videoStartNanos - audioBaseNanos) / 1000 -
            settings.audioOffsetMs * 1000L
        Thread {
            val audioOk = audio.endRecording()
            val result = try {
                if (failed && !v.exists()) "Kayıt alınamadı."
                else {
                    Muxer.merge(contentResolver, v, if (audioOk) a else null, shiftUs, recordName)
                    if (audioOk) "Video kaydedildi (yaka mikrofonu sesiyle): Galeri › LOVJA Prompter"
                    else "Video kaydedildi ama yaka mikrofonundan ses alınamadı."
                }
            } catch (t: Throwable) {
                Log.e(TAG, "merge", t)
                "Video birleştirilemedi: ${t.message}"
            }
            v.delete(); a.delete()
            runOnUiThread {
                busy = false
                flash(result, 8000)
                bindCamera()
            }
        }.start()
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
