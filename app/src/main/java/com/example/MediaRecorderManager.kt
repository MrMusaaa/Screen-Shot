package com.example

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaRecorderManager
 * 
 * Ekran görüntüsü akışını VirtualDisplay yüzeyinden (Surface) alarak
 * MP4 formatında video olarak kodlar ve kaydeder.
 */
class MediaRecorderManager(private val context: Context) {

    companion object {
        private const val TAG = "MediaRecorderManager"
    }

    private var mediaRecorder: MediaRecorder? = null
    private var tempVideoFile: File? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _recordingSeconds = MutableStateFlow(0)
    val recordingSeconds = _recordingSeconds.asStateFlow()

    /**
     * MediaRecorder'ı hazırlar ve VirtualDisplay'e bağlanacak giriş Surface'ını döndürür.
     */
    fun prepareRecorder(
        width: Int,
        height: Int,
        includeAudio: Boolean = false
    ): Surface {
        // Çift sayı olmalı (H264 encoder gereksinimi)
        val validWidth = if (width % 2 == 0) width else width - 1
        val validHeight = if (height % 2 == 0) height else height - 1

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ScreenRecord_$timeStamp.mp4"
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir
        tempVideoFile = File(storageDir, fileName)

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            if (includeAudio) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            recorder.setOutputFile(tempVideoFile!!.absolutePath)
            recorder.setVideoSize(validWidth, validHeight)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (includeAudio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128000)
                recorder.setAudioSamplingRate(44100)
            }
            recorder.setVideoEncodingBitRate(8 * 1024 * 1024) // 8 Mbps
            recorder.setVideoFrameRate(30)

            recorder.prepare()
            mediaRecorder = recorder
            return recorder.surface
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder hazırlama hatası: ${e.message}", e)
            recorder.release()
            throw e
        }
    }

    /**
     * Video kaydını başlatır.
     */
    fun startRecording() {
        try {
            mediaRecorder?.start()
            _isRecording.value = true
            _recordingSeconds.value = 0

            recordingJob?.cancel()
            recordingJob = scope.launch {
                while (isActive && _isRecording.value) {
                    delay(1000)
                    _recordingSeconds.value += 1
                }
            }
            Log.d(TAG, "Ekran video kaydı başladı.")
        } catch (e: Exception) {
            Log.e(TAG, "Kayıt başlatılamadı: ${e.message}", e)
            _isRecording.value = false
        }
    }

    /**
     * Video kaydını durdurur ve kaydedilen videoyu MediaStore galerisine aktarır.
     */
    fun stopRecording(): Uri? {
        recordingJob?.cancel()
        _isRecording.value = false

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder durdurulurken hata: ${e.message}", e)
        } finally {
            mediaRecorder = null
        }

        val file = tempVideoFile
        if (file == null || !file.exists() || file.length() == 0L) {
            Log.w(TAG, "Kayıt dosyası mevcut değil veya boş.")
            return null
        }

        // Dosyayı MediaStore Movies klasörüne taşı/kopyala
        val finalUri = exportVideoToGallery(file)
        Log.d(TAG, "Video kaydı tamamlandı ve kaydedildi: $finalUri")
        return finalUri
    }

    private fun exportVideoToGallery(file: File): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRecordings")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (videoUri != null) {
            try {
                resolver.openOutputStream(videoUri)?.use { outputStream: OutputStream ->
                    FileInputStream(file).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    outputStream.flush()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(videoUri, contentValues, null, null)
                }

                // Temp dosyayı temizle
                file.delete()
                return videoUri
            } catch (e: Exception) {
                Log.e(TAG, "Video galeriye aktarılırken hata: ${e.message}", e)
            }
        }
        return Uri.fromFile(file)
    }

    fun release() {
        try {
            if (_isRecording.value) {
                stopRecording()
            } else {
                mediaRecorder?.release()
                mediaRecorder = null
            }
        } catch (ignored: Exception) {}
    }
}
