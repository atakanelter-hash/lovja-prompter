package com.lovja.prompter

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Mikrofonu tek yerden okur: seviye göstergesi, ses testi ve (harici mikrofonda)
 * kayıt sırasında sesi ayrı bir dosyaya yazar. Sonra [Muxer] bu sesi videoyla birleştirir.
 *
 * Neden: Kameranın kendi kaydı (CameraX) her zaman telefonun iç mikrofonunu
 * kullanıyor; takılı yaka mikrofonunu seçmenin yolu yok.
 */
class AudioEngine(context: Context) {

    data class Mic(val id: Int, val label: String, val external: Boolean, val info: AudioDeviceInfo?)

    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    var onLevel: ((Float) -> Unit)? = null
    var onDevicesChanged: (() -> Unit)? = null
    var onTestState: ((String) -> Unit)? = null

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    var sampleRate = 48000; private set
    var channels = 1; private set
    var activeMic: Mic? = null; private set

    // Ses testi
    @Volatile private var testBuffer: ShortArray? = null
    @Volatile private var testPos = 0

    // Kayıt
    @Volatile private var recorder: AacFileWriter? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { main.post { onDevicesChanged?.invoke() } }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { main.post { onDevicesChanged?.invoke() } }
    }

    init {
        am.registerAudioDeviceCallback(deviceCallback, main)
    }

    fun release() {
        stop()
        am.unregisterAudioDeviceCallback(deviceCallback)
    }

    /** Takılı mikrofonlar. İlk sıradaki "Otomatik". */
    fun mics(): List<Mic> {
        val out = ArrayList<Mic>()
        out.add(Mic(-1, "Otomatik (takılıysa yaka mikrofonu)", false, null))
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { d ->
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Telefonun mikrofonu"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Kablolu"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
                else -> null
            } ?: return@forEach
            val external = d.type != AudioDeviceInfo.TYPE_BUILTIN_MIC
            // Telefonun birden çok iç mikrofonu olabilir; birini göstermek yeterli.
            if (!external && out.any { !it.external && it.id != -1 }) return@forEach
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() && external }
            out.add(Mic(d.id, if (name != null) "$kind: $name" else kind, external, d))
        }
        return out
    }

    /** Ayara göre kullanılacak mikrofon. */
    fun resolve(micId: Int): Mic {
        val list = mics()
        list.firstOrNull { it.id == micId && micId != -1 }?.let { return it }
        // Otomatik: USB > kablolu > Bluetooth > iç mikrofon
        val order = listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        for (t in order) list.firstOrNull { it.info?.type == t }?.let { return it }
        return list.firstOrNull { it.info?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?: list.first()
    }

    @SuppressLint("MissingPermission")
    fun start(mic: Mic) {
        stop()
        activeMic = mic
        val info = mic.info
        val bt = info != null && (info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || info.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        try {
            if (bt) am.setCommunicationDevice(info!!) else am.clearCommunicationDevice()
        } catch (t: Throwable) { Log.w(TAG, "communication device", t) }

        val rates = info?.sampleRates?.takeIf { it.isNotEmpty() }
        sampleRate = when {
            rates == null -> 48000
            48000 in rates -> 48000
            44100 in rates -> 44100
            else -> rates.max()
        }
        if (bt) sampleRate = rates?.firstOrNull { it == 16000 } ?: 16000
        val counts = info?.channelCounts
        channels = if (mic.external && !bt && (counts == null || counts.isEmpty() || 2 in counts)) 2 else 1
        val chMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val source = if (mic.external) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.CAMCORDER
        val r = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(chMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(max(minBuf * 4, sampleRate * channels * 2 / 5))
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord", t); null
        } ?: return
        if (info != null) r.preferredDevice = info
        try { r.startRecording() } catch (t: Throwable) { Log.e(TAG, "start", t); r.release(); return }
        record = r
        running = true
        thread = Thread({ loop(r) }, "mic").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.let { try { it.stop() } catch (_: Throwable) {}; it.release() }
        record = null
        try { am.clearCommunicationDevice() } catch (_: Throwable) {}
    }

    private fun loop(r: AudioRecord) {
        val frames = sampleRate / 50 // 20 ms
        val buf = ShortArray(frames * channels)
        val ts = AudioTimestamp()
        var framesRead = 0L
        var lastLevel = 0L
        var peak = 0
        while (running) {
            val n = r.read(buf, 0, buf.size)
            if (n <= 0) continue
            val nFrames = n / channels
            // Bu parçanın ilk örneğinin zamanı (System.nanoTime ile aynı saat)
            val t0 = if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / sampleRate
            } else {
                System.nanoTime() - nFrames * 1_000_000_000L / sampleRate
            }
            framesRead += nFrames

            for (i in 0 until n) { val a = abs(buf[i].toInt()); if (a > peak) peak = a }
            val now = System.currentTimeMillis()
            if (now - lastLevel > 60) {
                val level = peak / 32768f
                main.post { onLevel?.invoke(level) }
                peak = 0
                lastLevel = now
            }

            testBuffer?.let { tb ->
                val copy = minOf(n, tb.size - testPos)
                System.arraycopy(buf, 0, tb, testPos, copy)
                testPos += copy
                if (testPos >= tb.size) {
                    testBuffer = null
                    main.post { playTest(tb) }
                }
            }

            recorder?.write(buf, n, t0)
        }
    }

    // ---------- Ses testi ----------

    fun startTest(seconds: Int = 5) {
        if (!running) { onTestState?.invoke("Mikrofon çalışmıyor."); return }
        testPos = 0
        testBuffer = ShortArray(sampleRate * channels * seconds)
        onTestState?.invoke("Konuş… ($seconds sn kaydediliyor)")
    }

    private fun playTest(data: ShortArray) {
        onTestState?.invoke("Dinletiliyor…")
        Thread {
            try {
                val chOut = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(chOut)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(data.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(data, 0, data.size)
                track.play()
                Thread.sleep(data.size * 1000L / (sampleRate * channels) + 300)
                track.release()
                main.post { onTestState?.invoke("Test bitti. Sesin net geldiyse hazırsın.") }
            } catch (t: Throwable) {
                main.post { onTestState?.invoke("Dinletilemedi: ${t.message}") }
            }
        }.start()
    }

    // ---------- Kayıt ----------

    /** Kaydı başlatır. Zamanlar [baseNanos]'a göre (System.nanoTime) yazılır. */
    fun beginRecording(file: File, baseNanos: Long): Boolean {
        if (!running) return false
        return try {
            recorder = AacFileWriter(file, sampleRate, channels, baseNanos)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "beginRecording", t); false
        }
    }

    /** Kaydı bitirir; ses dosyası yazıldıysa true. */
    fun endRecording(): Boolean {
        val w = recorder ?: return false
        recorder = null
        Thread.sleep(50)
        return w.finish()
    }

    companion object { private const val TAG = "AudioEngine" }
}

/** PCM sesi AAC'ye çevirip tek başına bir ses dosyasına yazar. */
private class AacFileWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val baseNanos: Long,
) {
    private val codec: MediaCodec
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var started = false
    private var firstPtsUs = -1L
    private var framesWritten = 0L
    private val info = MediaCodec.BufferInfo()
    private var samplesWritten = 0

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 2) 256_000 else 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384 * channels)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    @Synchronized
    fun write(pcm: ShortArray, n: Int, chunkStartNanos: Long) {
        if (firstPtsUs < 0) firstPtsUs = (chunkStartNanos - baseNanos) / 1000
        var off = 0
        while (off < n) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) { drain(false); continue }
            val inBuf = codec.getInputBuffer(idx)!!
            inBuf.clear()
            inBuf.order(java.nio.ByteOrder.nativeOrder())
            val count = minOf((n - off), inBuf.capacity() / 2)
            for (i in 0 until count) inBuf.putShort(pcm[off + i])
            // Zaman damgası örnek sayısından: kesintisiz ve düzgün.
            val pts = firstPtsUs + framesWritten * 1_000_000L / sampleRate
            codec.queueInputBuffer(idx, 0, count * 2, pts, 0)
            framesWritten += count / channels
            off += count
            drain(false)
        }
    }

    private fun drain(end: Boolean) {
        var waits = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++waits > 100) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                        samplesWritten++
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    @Synchronized
    fun finish(): Boolean {
        return try {
            val idx = codec.dequeueInputBuffer(100_000)
            if (idx >= 0) {
                val pts = firstPtsUs + framesWritten * 1_000_000L / sampleRate
                codec.queueInputBuffer(idx, 0, 0, maxOf(pts, 0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
            codec.stop(); codec.release()
            if (started) muxer.stop()
            muxer.release()
            started && samplesWritten > 0
        } catch (t: Throwable) {
            Log.e("AacFileWriter", "finish", t)
            false
        }
    }
}
