package com.example.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * Boş RecognitionService uygulaması (VoiceInteractionService gereksinimi)
 */
class GeminiRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
