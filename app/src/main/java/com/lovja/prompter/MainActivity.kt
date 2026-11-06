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

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val engine = PrompterEngine()
    private lateinit var rear: RearDisplayHelper
    private var innerPrompter: PrompterView? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordStart = 0L

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
            b.timerText.text = String.format("● %02d:%02d", s / 60, s % 60)
            b.root.postDelayed(this, 500)
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
        engine.addListener { updateControls() }

        rear = RearDisplayHelper(
            activity = this,
            createContent = { ctx -> PrompterView(ctx, engine) },
            onStateChanged = { state, detail -> onRearState(state, detail) },
        )
        rear.startObserving()

        setupButtons()
        updateControls()
        showInnerPrompter(true)

        if (hasPermission(Manifest.permission.CAMERA)) startCamera()
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun setupButtons() {
        b.btnSlower.setOnClickListener { engine.wordsPerSecond -= 0.2f; saveSettings() }
        b.btnFaster.setOnClickListener { engine.wordsPerSecond += 0.2f; saveSettings() }
        b.btnSmaller.setOnClickListener { engine.textSizeSp -= 3f; saveSettings() }
        b.btnBigger.setOnClickListener { engine.textSizeSp += 3f; saveSettings() }
        b.btnRewind.setOnClickListener { engine.rewind() }
        b.btnPause.setOnClickListener { if (engine.playing) engine.pause() else engine.play() }
        b.btnRecord.setOnClickListener { if (recording == null) startRecording() else stopRecording() }
        b.btnRear.setOnClickListener {
            if (rear.state == RearDisplayHelper.State.ACTIVE) rear.stop()
            else { rear.autoStart = true; rear.start() }
        }
        b.btnText.setOnClickListener {
            b.scriptEdit.setText(engine.text)
            b.editorPanel.visibility = View.VISIBLE
        }
        b.btnPaste.setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val txt = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (txt.isNullOrBlank()) toast("Panoda metin yok.") else b.scriptEdit.setText(txt)
        }
        b.btnClear.setOnClickListener { b.scriptEdit.setText("") }
        b.btnDone.setOnClickListener {
            val t = b.scriptEdit.text.toString().trim()
            engine.pause()
            engine.setText(t)
            prefs.edit().putString("script", t).apply()
            b.editorPanel.visibility = View.GONE
            hideKeyboard()
        }
    }

    private fun saveSettings() {
        prefs.edit().putFloat("wps", engine.wordsPerSecond).putFloat("size", engine.textSizeSp).apply()
    }

    private fun updateControls() {
        val wpm = (engine.wordsPerSecond * 60).toInt()
        b.speedText.text = "$wpm kelime/dk"
        b.btnPause.text = if (engine.playing) "Yazıyı durdur" else "Yazıyı başlat"
    }

    private fun onRearState(state: RearDisplayHelper.State, detail: String) {
        b.statusText.text = when (state) {
            RearDisplayHelper.State.CHECKING -> "Dış ekran kontrol ediliyor…"
            RearDisplayHelper.State.ACTIVE -> "✅ Yazı dış ekranda gösteriliyor"
            RearDisplayHelper.State.AVAILABLE -> "Dış ekran hazır. “Dış ekranı aç”a bas."
            RearDisplayHelper.State.UNAVAILABLE -> "Dış ekran şu an kullanılamıyor. Telefonu tamamen aç."
            RearDisplayHelper.State.UNSUPPORTED -> "⚠️ Bu telefon dış ekrana yazı göndermeyi desteklemiyor görünüyor. Yazı iç ekranda gösteriliyor."
        }
        b.detailText.text = detail
        b.btnRear.text = if (state == RearDisplayHelper.State.ACTIVE) "Dış ekranı kapat" else "Dış ekranı aç"
        showInnerPrompter(state != RearDisplayHelper.State.ACTIVE)
    }

    private fun showInnerPrompter(show: Boolean) {
        if (show && innerPrompter == null) {
            innerPrompter = PrompterView(this, engine).also { b.innerPrompterHolder.addView(it) }
        } else if (!show && innerPrompter != null) {
            b.innerPrompterHolder.removeAllViews()
            innerPrompter = null
        }
        b.innerPrompterHolder.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---------- Kamera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.preview.surfaceProvider) }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.UHD, Quality.FHD, Quality.HD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                    )
                )
                .build()
            val capture = VideoCapture.withOutput(recorder)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                videoCapture = capture
            } catch (e: Exception) {
                toast("Kamera açılamadı: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: run { toast("Kamera hazır değil."); return }
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
        else toast("Mikrofon izni yok, video sessiz kaydedilecek.")

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordStart = SystemClock.elapsedRealtime()
                    b.timerText.visibility = View.VISIBLE
                    b.root.post(timerTick)
                    b.btnRecord.text = "Bitir"
                    engine.play()
                }
                is VideoRecordEvent.Finalize -> {
                    b.timerText.visibility = View.GONE
                    b.btnRecord.text = "Başla"
                    recording = null
                    if (event.hasError()) toast("Kayıtta sorun oldu (kod ${event.error}).")
                    else toast("Video kaydedildi: Galeri › LOVJA Prompter")
                }
            }
        }
    }

    private fun stopRecording() {
        engine.pause()
        recording?.stop()
    }

    override fun onStop() {
        // Uygulamadan çıkılırsa kaydı güvenle bitir.
        if (recording != null) stopRecording()
        super.onStop()
    }

    // ---------- Yardımcılar ----------

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(b.scriptEdit.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
