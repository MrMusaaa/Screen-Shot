package com.example

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptureState(
    val isProjectionReady: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isOverlayActive: Boolean = false,
    val isRecording: Boolean = false,
    val recordingDurationSec: Int = 0,
    val lastCapturedImageUri: Uri? = null,
    val lastCapturedVideoUri: Uri? = null,
    val statusMessage: String? = null
)

sealed interface CaptureEvent {
    object TriggerScreenshot : CaptureEvent
    object TriggerToggleRecording : CaptureEvent
    data class ShowToast(val message: String) : CaptureEvent
}

object CaptureStateManager {
    private val _state = MutableStateFlow(CaptureState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 10)
    val events = _events.asSharedFlow()

    // Store the MediaProjection permission result token
    var projectionResultCode: Int = 0
    var projectionData: Intent? = null

    fun setProjectionPermission(resultCode: Int, data: Intent?) {
        projectionResultCode = resultCode
        projectionData = data
        _state.value = _state.value.copy(
            isProjectionReady = resultCode != 0 && data != null
        )
    }

    fun updateServiceRunning(running: Boolean) {
        _state.value = _state.value.copy(isServiceRunning = running)
    }

    fun updateOverlayActive(active: Boolean) {
        _state.value = _state.value.copy(isOverlayActive = active)
    }

    fun updateRecording(isRecording: Boolean, durationSec: Int = 0) {
        _state.value = _state.value.copy(
            isRecording = isRecording,
            recordingDurationSec = durationSec
        )
    }

    fun setLastCapturedImage(uri: Uri?) {
        _state.value = _state.value.copy(
            lastCapturedImageUri = uri,
            statusMessage = if (uri != null) "Ekran görüntüsü kaydedildi!" else null
        )
    }

    fun setLastCapturedVideo(uri: Uri?) {
        _state.value = _state.value.copy(
            lastCapturedVideoUri = uri,
            statusMessage = if (uri != null) "Video kaydı kaydedildi!" else null
        )
    }

    fun triggerScreenshot() {
        _events.tryEmit(CaptureEvent.TriggerScreenshot)
    }

    fun triggerToggleRecording() {
        _events.tryEmit(CaptureEvent.TriggerToggleRecording)
    }

    fun showToast(message: String) {
        _events.tryEmit(CaptureEvent.ShowToast(message))
    }
}
