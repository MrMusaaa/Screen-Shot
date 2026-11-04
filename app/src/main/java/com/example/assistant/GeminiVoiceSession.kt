package com.example.assistant

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.example.CaptureStateManager
import com.example.FrameCaptureHelper

/**
 * GeminiVoiceSession
 * 
 * Güç tuşuna basılı tutulduğunda Android OS tarafından doğrudan çağrılır.
 * Android sistemi ekranın görüntüsünü [onHandleScreenshot] metoduna Bitmap olarak
 * doğrudan teslim eder. Bu yöntem hiçbir üçüncü taraf uygulamanın ekran görüntüsü
 * dedektörünü (ScreenCaptureCallback veya ContentObserver) tetiklemez.
 */
class GeminiVoiceSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "GeminiVoiceSession"
    }

    override fun onCreate() {
        super.onCreate()
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "Gemini Asistan oturumu açıldı (Güç tuşu veya asistan tetikleyici).")
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null) {
            Log.d(TAG, "Android OS tarafından ekran görüntüsü sağlandı (${screenshot.width}x${screenshot.height}).")
            val uri = FrameCaptureHelper.saveBitmapToPrivateStorage(context, screenshot)
            if (uri != null) {
                CaptureStateManager.notifyCaptureResult(uri)
                CaptureStateManager.showToast("Gemini Asistan: Ekran görüntüsü gizlice kaydedildi!")
            } else {
                CaptureStateManager.showToast("Ekran görüntüsü kaydedilemedi.")
            }
        } else {
            Log.w(TAG, "Android OS ekran görüntüsü sağlamadı (FLAG_SECURE veya izin kısıtı).")
            CaptureStateManager.showToast("Ekran görüntüsü alınamadı.")
        }
        hide()
    }
}
