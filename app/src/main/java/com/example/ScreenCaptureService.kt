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

    private fun registerPowerButtonReceiver() {
        if (powerButtonReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        powerButtonReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        lastScreenOffTime = System.currentTimeMillis()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        val now = System.currentTimeMillis()
                        val diff = now - lastScreenOffTime
                        // Kullanıcı güç tuşuna hızlıca iki kez bastığında ekran kapanıp açılır (80-1400ms)
                        if (diff in 80..1400) {
                            lastScreenOffTime = 0L
                            Log.d(TAG, "⚡ Güç tuşuna 2 kez hızlı basıldı (Ekran kapandı-açıldı: ${diff}ms)")
                            toggleRecording()
                        }
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

    private fun toggleRecording() {
        if (CaptureStateManager.state.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val proj = mediaProjection ?: run {
            CaptureStateManager.showToast("Ekran izni aktif değil!")
            return
        }
        val recorder = mediaRecorderManager ?: return
        try {
            setupDisplayMetrics()
            val surface = recorder.prepareRecorder(screenWidth, screenHeight, includeAudio)
            virtualDisplay?.release()
            virtualDisplay = proj.createVirtualDisplay(
                "ScreenCapture_VideoRecording",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
            recorder.startRecording()
            CaptureStateManager.updateRecording(isRecording = true, durationSec = 0)
            updateNotification("Ekran video kaydı yapılıyor...")
            vibrateFeedback(longArrayOf(0, 80, 80, 80))
            CaptureStateManager.showToast("🎥 Ekran kaydı başladı")
        } catch (e: Exception) {
            Log.e(TAG, "Kayıt başlatılamadı: ${e.message}", e)
            CaptureStateManager.showToast("Kayıt başlatılamadı: ${e.message}")
            virtualDisplay?.release()
            virtualDisplay = null
        }
    }

    private fun stopRecording() {
        val recorder = mediaRecorderManager ?: return
        serviceScope.launch {
            val videoUri = withContext(Dispatchers.IO) {
                recorder.stopRecording()
            }
            virtualDisplay?.release()
            virtualDisplay = null

            CaptureStateManager.updateRecording(isRecording = false, durationSec = 0)
            updateNotification("Ekran Yakalama ve Yüzen Menü Aktif")
            vibrateFeedback(longArrayOf(0, 120))

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
        unregisterPowerButtonReceiver()
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
