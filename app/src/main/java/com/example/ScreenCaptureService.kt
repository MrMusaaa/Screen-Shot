package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * ScreenCaptureService
 * 
 * MediaProjection API kullanarak ekran görüntüsü ve video akışını
 * arka planda yöneten Foreground Service.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 2048

        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_CAPTURE = "com.example.action.CAPTURE"
        const val ACTION_START_RECORD = "com.example.action.START_RECORD"
        const val ACTION_STOP_RECORD = "com.example.action.STOP_RECORD"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_INCLUDE_AUDIO = "extra_include_audio"

        fun startService(context: Context, resultCode: Int, data: Intent, includeAudio: Boolean = false) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_INCLUDE_AUDIO, includeAudio)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun captureScreenshot(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_CAPTURE
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorderManager: MediaRecorderManager? = null

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 1920
    private var screenDensity: Int = 420
    private var includeAudio: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    private var powerButtonReceiver: BroadcastReceiver? = null
    private var lastScreenOffTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaRecorderManager = MediaRecorderManager(this)
        registerPowerButtonReceiver()

        // Dinleyici: CaptureStateManager üzerindeki aksiyonları dinle
        CaptureStateManager.events.onEach { event ->
            when (event) {
                is CaptureEvent.TriggerScreenshot -> takeScreenshot()
                is CaptureEvent.TriggerToggleRecording -> toggleRecording()
                else -> {}
            }
        }.launchIn(serviceScope)

        // Dinleyici: Kayıt süresini CaptureStateManager'a aktar
        mediaRecorderManager?.recordingSeconds?.onEach { sec ->
            if (CaptureStateManager.state.value.isRecording) {
                CaptureStateManager.updateRecording(isRecording = true, durationSec = sec)
            }
        }?.launchIn(serviceScope)
    }

    private var powerPressCount = 0
    private var lastPowerPressTime = 0L

    private fun registerPowerButtonReceiver() {
        if (powerButtonReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        powerButtonReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val now = System.currentTimeMillis()
                val action = intent?.action ?: return

                if (action == Intent.ACTION_SCREEN_OFF) {
                    lastScreenOffTime = now
                    powerPressCount++
                    lastPowerPressTime = now
                } else if (action == Intent.ACTION_SCREEN_ON) {
                    powerPressCount++
                    val diff = now - lastScreenOffTime
                    lastPowerPressTime = now

                    // Ekran hızlıca kapandı ve açıldı (1 güç tuşu döngüsü veya 2. tıklama)
                    Log.d(TAG, "⚡ Güç tuşu aktivitesi algılandı: count=$powerPressCount, diff=${diff}ms")
                }

                // 2 kez güç tuşuna basıldığında ekran durumu değişir (OFF -> ON veya hızlı basışlar)
                // 1500ms içerisinde 2 kez durum değişimi veya kapandı-açıldı = Çift Basış!
                if (powerPressCount >= 2 || (now - lastScreenOffTime in 80..1800)) {
                    val timeDiff = now - lastPowerPressTime
                    if (timeDiff < 2000) {
                        powerPressCount = 0
                        lastScreenOffTime = 0L
                        Log.d(TAG, "⚡ Güç tuşuna 2 kez basıldı -> Video Kaydını Değiştir (Başlat / Durdur)")
                        toggleRecording()
                    }
                }
            }
        }
        try {
            registerReceiver(powerButtonReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "PowerButtonReceiver kayıt hatası: ${e.message}")
        }
    }

    private fun unregisterPowerButtonReceiver() {
        powerButtonReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (ignored: Exception) {}
            powerButtonReceiver = null
        }
    }

    private fun vibrateFeedback(pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (ignored: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                includeAudio = intent.getBooleanExtra(EXTRA_INCLUDE_AUDIO, false)

                if (resultCode != 0 && data != null) {
                    startForegroundServiceWithNotification()
                    initMediaProjection(resultCode, data)
                } else if (CaptureStateManager.projectionResultCode != 0 && CaptureStateManager.projectionData != null) {
                    startForegroundServiceWithNotification()
                    initMediaProjection(CaptureStateManager.projectionResultCode, CaptureStateManager.projectionData!!)
                } else {
                    Log.e(TAG, "MediaProjection izni bilgileri eksik.")
                    stopSelf()
                }
            }
            ACTION_CAPTURE -> {
                takeScreenshot()
            }
            ACTION_START_RECORD -> {
                startRecording()
            }
            ACTION_STOP_RECORD -> {
                stopRecording()
            }
            ACTION_STOP -> {
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildNotification("Ekran Yakalama ve Yüzen Menü Aktif")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        CaptureStateManager.updateServiceRunning(true)
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Overlay Capture")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        // Android 14+ zorunlu callback kaydı
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection oturumu durduruldu.")
                cleanup()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        setupDisplayMetrics()
        CaptureStateManager.updateServiceRunning(true)
        Log.d(TAG, "MediaProjection oturumu hazır (İstek üzerine ekran yakalama devrede).")
    }

    private fun setupDisplayMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
            screenDensity = dm.densityDpi
        }
    }

    private fun takeScreenshot() {
        val proj = mediaProjection
        if (proj == null) {
            Log.e(TAG, "MediaProjection null, ekran görüntüsü alınamaz.")
            CaptureStateManager.notifyCaptureResult(null)
            CaptureStateManager.showToast("Ekran servisi aktif değil! Lütfen ana uygulamadan ekran izni verin.")
            return
        }

        serviceScope.launch {
            // Butonların ve animasyonun temizlenmesi için kısa gecikme
            delay(120)
            setupDisplayMetrics()

            val uri = FrameCaptureHelper.captureFrameOnDemand(
                mediaProjection = proj,
                context = applicationContext,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                screenDensity = screenDensity
            )

            if (uri != null) {
                CaptureStateManager.notifyCaptureResult(uri)
                CaptureStateManager.showToast("Ekran görüntüsü kaydedildi!")
            } else {
                CaptureStateManager.notifyCaptureResult(null)
                CaptureStateManager.showToast("Ekran görüntüsü alınamadı.")
            }
        }
    }

    private var burstRecordJob: Job? = null
    private var burstTempDir: File? = null
    private val burstFrames = mutableListOf<File>()
    private var burstHandlerThread: android.os.HandlerThread? = null

    private fun toggleRecording() {
        if (CaptureStateManager.state.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val proj = mediaProjection ?: run {
            CaptureStateManager.showToast("Ekran izni aktif değil! Lütfen uygulamadan izin verin.")
            return
        }

        if (CaptureStateManager.state.value.isRecording) return

        try {
            setupDisplayMetrics()

            // Geçici kare klasörü oluştur
            val tempDir = File(cacheDir, "burst_rec_${System.currentTimeMillis()}").apply {
                if (!exists()) mkdirs()
            }
            burstTempDir = tempDir
            burstFrames.clear()

            // 720p hedef çözünürlük (performans ve sıfır donma için)
            val targetWidth = 720
            val targetHeight = (720 * screenHeight / screenWidth).let { if (it % 2 == 0) it else it - 1 }
            val captureDensity = (screenDensity * targetWidth / screenWidth)

            // HandlerThread ile arka planda kesintisiz kare dinleyicisi
            val handlerThread = HandlerThread("BurstCaptureThread").apply { start() }
            val bgHandler = Handler(handlerThread.looper)
            this.burstHandlerThread = handlerThread

            val imageReader = ImageReader.newInstance(
                targetWidth,
                targetHeight,
                PixelFormat.RGBA_8888,
                3
            )
            this.imageReader = imageReader

            var frameIndex = 0
            var lastCaptureTime = 0L
            val frameIntervalMs = 200L // 5 FPS

            imageReader.setOnImageAvailableListener({ reader ->
                if (!CaptureStateManager.state.value.isRecording) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastCaptureTime < frameIntervalMs) {
                    try {
                        reader.acquireLatestImage()?.close()
                    } catch (ignored: Exception) {}
                    return@setOnImageAvailableListener
                }

                val img = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    null
                } ?: return@setOnImageAvailableListener

                lastCaptureTime = now

                try {
                    val planes = img.planes
                    if (planes.isNotEmpty()) {
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * targetWidth
                        val bufferWidth = targetWidth + (if (pixelStride > 0) rowPadding / pixelStride else 0)

                        val rawBitmap = Bitmap.createBitmap(
                            bufferWidth,
                            targetHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        rawBitmap.copyPixelsFromBuffer(buffer)
                        img.close()

                        val cleanBitmap = if (rowPadding > 0 || bufferWidth != targetWidth) {
                            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, targetWidth, targetHeight)
                            rawBitmap.recycle()
                            cropped
                        } else {
                            rawBitmap
                        }

                        val frameFile = File(tempDir, String.format(Locale.US, "frame_%05d.jpg", frameIndex++))
                        FileOutputStream(frameFile).use { out ->
                            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        cleanBitmap.recycle()
                        synchronized(burstFrames) {
                            burstFrames.add(frameFile)
                        }
                    } else {
                        img.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Burst kare yakalama hatası: ${e.message}")
                    try { img.close() } catch (ignored: Exception) {}
                }
            }, bgHandler)

            val vDisplay = proj.createVirtualDisplay(
                "ScreenCapture_BurstRecording",
                targetWidth,
                targetHeight,
                captureDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                bgHandler
            )
            this.virtualDisplay = vDisplay

            CaptureStateManager.updateRecording(isRecording = true, durationSec = 0)
            updateNotification("Sessiz ekran kareleri kaydediliyor...")
            vibrateFeedback(longArrayOf(0, 80, 80, 80))
            CaptureStateManager.showToast("🎥 Sessiz Ekran Kaydı Başladı (Sıfır Bildirim)")

            var secondsCounter = 0
            burstRecordJob = serviceScope.launch(Dispatchers.Default) {
                val startTime = System.currentTimeMillis()
                while (CaptureStateManager.state.value.isRecording) {
                    delay(500)
                    val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                    if (elapsedSec != secondsCounter) {
                        secondsCounter = elapsedSec
                        CaptureStateManager.updateRecording(isRecording = true, durationSec = secondsCounter)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Sessiz kayıt başlatılamadı: ${e.message}", e)
            CaptureStateManager.showToast("Kayıt başlatılamadı: ${e.message}")
            stopBurstRecordingInternal()
        }
    }

    private fun stopRecording() {
        if (!CaptureStateManager.state.value.isRecording) return

        CaptureStateManager.updateRecording(isRecording = false, durationSec = 0)
        updateNotification("Ekran Yakalama ve Yüzen Menü Aktif")
        vibrateFeedback(longArrayOf(0, 120))
        CaptureStateManager.showToast("⏹ Kayıt tamamlandı, video oluşturuluyor...")

        burstRecordJob?.cancel()
        burstRecordJob = null

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        try {
            burstHandlerThread?.quitSafely()
        } catch (ignored: Exception) {}
        burstHandlerThread = null

        serviceScope.launch {
            val framesToEncode = synchronized(burstFrames) { burstFrames.toList() }
            val tempDir = burstTempDir

            if (framesToEncode.isEmpty()) {
                CaptureStateManager.showToast("Kaydedilecek kare bulunamadı.")
                tempDir?.deleteRecursively()
                return@launch
            }

            val capturesDir = File(filesDir, "captures").apply {
                if (!exists()) mkdirs()
            }
            val videoFile = File(capturesDir, "vid_${System.currentTimeMillis()}.mp4")

            val success = withContext(Dispatchers.IO) {
                FrameVideoEncoder.encodeFramesToMp4(
                    frameFiles = framesToEncode,
                    outputFile = videoFile,
                    fps = 5,
                    targetWidth = 720,
                    targetHeight = (720 * screenHeight / screenWidth).let { if (it % 2 == 0) it else it - 1 }
                )
            }

            tempDir?.deleteRecursively()
            burstFrames.clear()

            if (success && videoFile.exists() && videoFile.length() > 0) {
                val authority = "$packageName.fileprovider"
                val uri = androidx.core.content.FileProvider.getUriForFile(applicationContext, authority, videoFile)
                CaptureStateManager.setLastCapturedVideo(uri)
                CaptureStateManager.showToast("🎬 Video başarıyla kaydedildi! (Karşı tarafa sıfır bildirim)")
            } else {
                CaptureStateManager.showToast("Video kodlanamadı.")
            }
        }
    }

    private fun stopBurstRecordingInternal() {
        burstRecordJob?.cancel()
        burstRecordJob = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        try {
            burstHandlerThread?.quitSafely()
        } catch (ignored: Exception) {}
        burstHandlerThread = null
        burstTempDir?.deleteRecursively()
        burstFrames.clear()
        CaptureStateManager.updateRecording(isRecording = false, durationSec = 0)
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ekran Yakalama Servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ekran görüntüsü ve video yakalama arka plan bildirimi"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun cleanup() {
        unregisterPowerButtonReceiver()
        try {
            stopBurstRecordingInternal()
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Temizleme hatası: ${e.message}", e)
        } finally {
            CaptureStateManager.updateServiceRunning(false)
            CaptureStateManager.updateRecording(false)
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
