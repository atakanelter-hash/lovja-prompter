package com.lovja.prompter

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

/** Sessiz videoyu ayrı kaydedilen sesle birleştirip Galeri'ye kaydeder. */
object Muxer {

    /**
     * @param audioShiftUs ses dosyasındaki zamanlardan çıkarılacak miktar
     *        (videonun ilk karesinin, ses dosyasının zaman çizgisindeki yeri).
     */
    fun merge(
        resolver: ContentResolver,
        video: File,
        audio: File?,
        audioShiftUsIn: Long,
        displayName: String,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LOVJA Prompter")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Galeri'ye yazılamadı")
        try {
            resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val rotation = MediaMetadataRetriever().run {
                    try {
                        setDataSource(video.absolutePath)
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    } finally { release() }
                }
                muxer.setOrientationHint(rotation)

                val vEx = MediaExtractor().apply { setDataSource(video.absolutePath) }
                val vTrack = (0 until vEx.trackCount).first {
                    vEx.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/")
                }
                vEx.selectTrack(vTrack)
                val outV = muxer.addTrack(vEx.getTrackFormat(vTrack))

                var aEx: MediaExtractor? = null
                var outA = -1
                if (audio != null && audio.exists() && audio.length() > 0) {
                    aEx = MediaExtractor().apply { setDataSource(audio.absolutePath) }
                    val aTrack = (0 until aEx.trackCount).firstOrNull {
                        aEx.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("audio/")
                    }
                    if (aTrack != null) {
                        aEx.selectTrack(aTrack)
                        outA = muxer.addTrack(aEx.getTrackFormat(aTrack))
                    } else { aEx.release(); aEx = null }
                }
                muxer.start()
                // Video dosyası 0'dan başlamıyorsa ses de ona göre kaysın.
                val audioShiftUs = audioShiftUsIn - vEx.sampleTime.coerceAtLeast(0)

                val buf = ByteBuffer.allocateDirect(16 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()

                // Videonun süresi: ses bundan uzun olmasın.
                var videoEndUs = 0L
                var vDone = false
                var aDone = aEx == null

                // Sesin başındaki fazlalığı atla
                aEx?.let { ex ->
                    while (ex.sampleTime in 0 until audioShiftUs) { if (!ex.advance()) { aDone = true; break } }
                }

                while (!vDone || !aDone) {
                    val vt = if (vDone) Long.MAX_VALUE else vEx.sampleTime
                    val at = if (aDone) Long.MAX_VALUE else aEx!!.sampleTime - audioShiftUs
                    if (!vDone && (vt <= at)) {
                        val size = vEx.readSampleData(buf, 0)
                        if (size < 0) { vDone = true; continue }
                        info.set(0, size, vt,
                            if (vEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(outV, buf, info)
                        videoEndUs = maxOf(videoEndUs, vt)
                        if (!vEx.advance()) vDone = true
                    } else if (!aDone) {
                        val ex = aEx!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0 || (vDone && at > videoEndUs)) { aDone = true; continue }
                        info.set(0, size, at, 0)
                        muxer.writeSampleData(outA, buf, info)
                        if (!ex.advance()) aDone = true
                    }
                }
                muxer.stop()
                muxer.release()
                vEx.release()
                aEx?.release()
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
