package com.handcode.app.ui.screen.camera

import com.handcode.app.domain.model.OcrResult

/**
 * 카메라 화면의 UI 상태.
 * Idle → Processing → (Recognized | Error)
 */
sealed interface CameraUiState {
    data object Idle : CameraUiState
    data object Processing : CameraUiState
    data class Recognized(val result: OcrResult) : CameraUiState
    data class Error(val message: String) : CameraUiState
}
