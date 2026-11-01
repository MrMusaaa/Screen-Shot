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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaRecorderManager = MediaRecorderManager(this)

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
        setupImageReader()
        setupVirtualDisplay()
    }

    private fun setupDisplayMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars()
            )
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

    private fun setupImageReader() {
        imageReader?.close()
        // PixelFormat.RGBA_8888 ile bellek tamponu hazırlanıyor
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
    }

    private fun setupVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture_Display",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        Log.d(TAG, "VirtualDisplay başarıyla kuruldu. Çözünürlük: ${screenWidth}x${screenHeight}")
    }

    private fun takeScreenshot() {
        val reader = imageReader ?: return
        serviceScope.launch {
            // Küçük bir bekleme: Eğer yüzen buton gizleniyorsa ekran çiziminin tamamlanmasını bekle
            delay(120)

            val uri = FrameCaptureHelper.captureAndSaveFrame(
                imageReader = reader,
                context = applicationContext,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )

            if (uri != null) {
                CaptureStateManager.setLastCapturedImage(uri)
                CaptureStateManager.showToast("Ekran görüntüsü galeriye kaydedildi!")
            } else {
                CaptureStateManager.showToast("Ekran görüntüsü alınamadı.")
            }
        }
    }

    private fun toggleRecording() {
        if (CaptureStateManager.state.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val recorder = mediaRecorderManager ?: return
        try {
            val surface = recorder.prepareRecorder(screenWidth, screenHeight, includeAudio)
            // VirtualDisplay yüzeyini MediaRecorder'a aktar
            virtualDisplay?.surface = surface
            recorder.startRecording()
            CaptureStateManager.updateRecording(isRecording = true, durationSec = 0)
            updateNotification("Ekran video kaydı yapılıyor...")
            CaptureStateManager.showToast("Ekran kaydı başladı")
        } catch (e: Exception) {
            Log.e(TAG, "Kayıt başlatılamadı: ${e.message}", e)
            CaptureStateManager.showToast("Kayıt başlatılamadı: ${e.message}")
            // Geri ImageReader'a bağla
            virtualDisplay?.surface = imageReader?.surface
        }
    }

    private fun stopRecording() {
        val recorder = mediaRecorderManager ?: return
        serviceScope.launch {
            val videoUri = withContext(Dispatchers.IO) {
                recorder.stopRecording()
            }
            // VirtualDisplay yüzeyini tekrar ImageReader'a döndür
            virtualDisplay?.surface = imageReader?.surface
            CaptureStateManager.updateRecording(isRecording = false, durationSec = 0)
            updateNotification("Ekran Yakalama ve Yüzen Menü Aktif")

            if (videoUri != null) {
                CaptureStateManager.setLastCapturedVideo(videoUri)
                CaptureStateManager.showToast("Video kaydı kaydedildi!")
            }
        }
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
        try {
            if (CaptureStateManager.state.value.isRecording) {
                mediaRecorderManager?.stopRecording()
            }
            mediaRecorderManager?.release()
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
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
