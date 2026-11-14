package com.handcode.app.ui.screen.camera

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handcode.app.R
import com.handcode.app.data.image.ImagePreparer
import com.handcode.app.data.pipeline.PipelineStore
import com.handcode.app.domain.usecase.RecognizeCodeUseCase
import com.handcode.app.ui.screen.pipeline.FailureReason
import com.handcode.app.ui.screen.pipeline.PipelineState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 카메라 촬영 / 갤러리 선택 → 이미지 정리 → OCR 인식 → PipelineStore 세션 시작.
 *
 * 5주차 변경점:
 * - 인식 중/실패 상태를 [PipelineStore.pipelineState] 에 직접 반영한다.
 *   (Editor/Result 화면의 PipelineViewModel 들이 자동으로 같은 상태를 관찰)
 * - 인식 성공 시 `startWith(ocr)` 가 내부적으로 Editing 상태로 전이시킨다.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val recognizeCode: RecognizeCodeUseCase,
    private val pipelineStore: PipelineStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<CameraUiState>(CameraUiState.Idle)
    val uiState: StateFlow<CameraUiState> = _uiState

    /** 촬영 완료 콜백에서 받은 파일 경로를 처리. */
    fun onImageCaptured(path: String) {
        viewModelScope.launch {
            _uiState.value = CameraUiState.Processing
            val prepared = ImagePreparer.prepare(path)
            if (prepared == null) {
                failWith(appContext.getString(R.string.camera_image_load_failed))
                return@launch
            }
            runRecognition(sourcePath = path) { recognizeCode(prepared) }
        }
    }

    /** 갤러리(Photo Picker)에서 선택된 Uri 처리. */
    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = CameraUiState.Processing
            val prepared = ImagePreparer.prepareFromUri(appContext, uri)
            if (prepared == null) {
                failWith(appContext.getString(R.string.camera_gallery_image_load_failed))
                return@launch
            }
            // content Uri 는 BitmapFactory.decodeFile 호환 경로가 아니라 원본 뷰에서 띄우지 않는다.
            runRecognition(sourcePath = null) { recognizeCode(prepared) }
        }
    }

    /** 결과 화면으로 넘어간 후 상태 리셋. */
    fun consumeResult() {
        _uiState.value = CameraUiState.Idle
    }

    /**
     * OCR 실패 / 에러 상태를 리셋해 다시 촬영할 수 있도록 한다.
     * 사용자가 "다시 촬영" 버튼을 누르면 호출한다.
     */
    fun reset() {
        _uiState.value = CameraUiState.Idle
    }

    /**
     * CameraX ImageCapture 실패 시 UI 상태를 Error 로 전이.
     * CameraScreen 의 OnImageSavedCallback.onError 에서 호출한다.
     * "조용한 실패"를 방지해 사용자에게 원인을 그대로 전달한다.
     */
    fun onCaptureFailed(message: String) {
        _uiState.value = CameraUiState.Error(
            appContext.getString(R.string.camera_capture_failed_fmt, message)
        )
    }

    /**
     * 인식 공통 처리. 성공 시 PipelineStore 에 세션 시작 후 Recognized 상태.
     * @param sourcePath BitmapFactory.decodeFile 로 다시 열 수 있는 로컬 파일 경로.
     *   null 이면 F7 원본 이미지 뷰어가 비활성화된다.
     */
    private suspend fun runRecognition(
        sourcePath: String?,
        block: suspend () -> com.handcode.app.domain.model.OcrResult
    ) {
        // 파이프라인 상태도 Recognizing 으로 전환하여, Editor 진입 시 잔상 최소화.
        pipelineStore.setPipelineState(PipelineState.Recognizing())
        val result = runCatching { block() }.getOrElse {
            failWith(
                it.message ?: appContext.getString(R.string.camera_recognition_failed),
                reason = FailureReason.OCR_FAILED
            )
            return
        }
        // startWith 는 내부에서 pipelineState 를 Editing 으로 전이.
        pipelineStore.startWith(result)
        sourcePath?.let { pipelineStore.setOriginalImagePath(it) }
        _uiState.value = CameraUiState.Recognized(result)
    }

    /** UI 에러 + 파이프라인 Failed 상태 동기화. */
    private fun failWith(
        message: String,
        reason: FailureReason = FailureReason.OCR_FAILED
    ) {
        _uiState.value = CameraUiState.Error(message)
        pipelineStore.setPipelineState(PipelineState.Failed(reason, session = null))
    }
}
