package com.example.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.example.CaptureStateManager
import com.example.FrameCaptureHelper
import com.example.MainActivity

/**
 * GeminiVoiceSession
 * 
 * Android Cihaz Asistanı (Power Tuşu Eylemleri):
 * 1. 1 kez basılı tutma -> Gizlice Ekran Görüntüsü Alır (Bildirimsiz)
 * 2. 2 kez hızlıca basma -> Gizlice Ekran Video Kaydını Başlatır
 * 3. Kayıt sırasında 2 kez basma -> Ekran Video Kaydını Durdurur ve Kaydeder
 * 
 * Tüm yakalamalar uygulamanın özel kum havuzuna (Sandbox) kaydedildiği için
 * Instagram, Snapchat, WhatsApp veya bankacılık uygulamaları karşı tarafa
 * ASLA bildirim gönderemez.
 */
class GeminiVoiceSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "GeminiVoiceSession"

        private val mainHandler = Handler(Looper.getMainLooper())
        private var lastTriggerTime: Long = 0L
        private var lastStopPressTime: Long = 0L
        private var pendingScreenshotRunnable: Runnable? = null
        private var pendingScreenshotBitmap: Bitmap? = null

        private fun vibratePulse(context: Context, pattern: LongArray) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                } ?: return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Titreşim hatası: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val now = System.currentTimeMillis()
        val isRecording = CaptureStateManager.state.value.isRecording

        if (isRecording) {
            // Kayıt devam ediyorsa asistanı açmak kaydı anında durdurur ve kaydeder
            CaptureStateManager.triggerStopRecording()
            vibratePulse(context, longArrayOf(0, 100, 80, 100))
            CaptureStateManager.showToast("⏹ Ekran kaydı durduruldu ve gizlice kaydedildi!")
            hide()
            return
        }

        // DURUM A: Kayıt Yok (Ekran Görüntüsü veya Kayıt Başlatma)
        val timeSinceTrigger = now - lastTriggerTime
        if (timeSinceTrigger in 100..3500) {
            // 2. Basış / Tetikleme Algılandı -> EKRAN KAYDINI BAŞLAT
            lastTriggerTime = 0L
            // Bekleyen ekran görüntüsü görevini iptal et
            pendingScreenshotRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingScreenshotRunnable = null
            pendingScreenshotBitmap = null

            // Servisin ve iznin hazır olup olmadığını kontrol et
            val state = CaptureStateManager.state.value
            if (!state.isServiceRunning || !state.isProjectionReady) {
                CaptureStateManager.showToast("⚠️ Ekran kaydı için lütfen uygulamadan bir defalık ekran iznini açın!")
                val appIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(appIntent)
                vibratePulse(context, longArrayOf(0, 150))
                hide()
                return
            }

            // Ekran kaydını başlat
            CaptureStateManager.triggerStartRecording()
            vibratePulse(context, longArrayOf(0, 80, 80, 80))
            CaptureStateManager.showToast("🎥 Gizli Ekran Kaydı Başladı (Durdurmak için 2 kez basın)")
            hide()
        } else {
            // 1. Basış -> Ekran görüntüsü için zamanlayıcı kur
            lastTriggerTime = now
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot == null) {
            hide()
            return
        }

        val isRecording = CaptureStateManager.state.value.isRecording
        if (isRecording) {
            hide()
            return
        }

        // İkinci bir basış (ekran kaydı isteği) gelip gelmeyeceğini 750ms bekle
        pendingScreenshotBitmap = screenshot
        pendingScreenshotRunnable?.let { mainHandler.removeCallbacks(it) }

        val runnable = Runnable {
            if (!CaptureStateManager.state.value.isRecording) {
                val uri = FrameCaptureHelper.saveBitmapToPrivateStorage(context, screenshot)
                if (uri != null) {
                    vibratePulse(context, longArrayOf(0, 70))
                    CaptureStateManager.notifyCaptureResult(uri)
                    CaptureStateManager.showToast("📸 Ekran görüntüsü gizlice kaydedildi!")
                }
            }
            pendingScreenshotBitmap = null
            pendingScreenshotRunnable = null
        }

        pendingScreenshotRunnable = runnable
        mainHandler.postDelayed(runnable, 750)
        hide()
    }
}
