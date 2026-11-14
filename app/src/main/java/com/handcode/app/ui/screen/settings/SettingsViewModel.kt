package com.handcode.app.ui.screen.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handcode.app.data.ocr.mediapipe.GemmaModelManager
import com.handcode.app.data.ocr.mediapipe.LiteRtLmModelInstaller
import com.handcode.app.data.preferences.SettingsRepository
import com.handcode.app.domain.model.AppSettings
import com.handcode.app.domain.model.FontSize
import com.handcode.app.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

private const val STATE_IN_TIMEOUT_MS = 5_000L

/**
 * 설정 화면 ViewModel.
 *
 * 초기값은 [AppSettings.Default] 를 사용한다. DataStore 의 첫 값이 거의 즉시 도달하므로
 * 체감 깜빡임은 무시할 수 있는 수준이다. (메인 스레드 runBlocking 제거 → ANR 위험 해소)
 *
 * ## 2026-04-19 MediaPipe GenAI 전환
 *  - 기존 VLM 언로드/진단 섹션 (llama.cpp native handle 기반) 제거.
 *  - SAF 기반 `.litertlm` 복사 지원.
 *
 * ## 2026-04-19 자동 다운로드 하이브리드 확장
 *  - URL 기반 자동 다운로드를 최우선 경로로 추가.
 *  - SAF 폴백은 그대로 유지 — 인터넷이 없거나 대용량 파일을 미리 받아둔 사용자 대응.
 *  - 진행률 / 다운로드 실패 / 라이선스 수락 상태 등을 UI 에 Flow 로 노출.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    private val modelManager: GemmaModelManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_IN_TIMEOUT_MS),
            initialValue = AppSettings.Default
        )

    val gemmaModelUrl: StateFlow<String> = repository.gemmaModelUrl
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_IN_TIMEOUT_MS),
            initialValue = GemmaModelManager.DEFAULT_URL_PLACEHOLDER
        )

    val gemmaModelSha256: StateFlow<String> = repository.gemmaModelSha256
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_IN_TIMEOUT_MS),
            initialValue = ""
        )

    val gemmaLicenseAccepted: StateFlow<Boolean> = repository.gemmaLicenseAccepted
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_IN_TIMEOUT_MS),
            initialValue = false
        )

    private val _modelInstalled = MutableStateFlow(LiteRtLmModelInstaller.isInstalled(context))
    val modelInstalled: StateFlow<Boolean> = _modelInstalled.asStateFlow()

    // SAF 복사 중 상태 (기존 호환). 자동 다운로드는 downloadProgress 로 별도 표현.
    private val _modelInstalling = MutableStateFlow(false)
    val modelInstalling: StateFlow<Boolean> = _modelInstalling.asStateFlow()

    /**
     * 다운로드 진행률 0.0~1.0. null 이면 미진행.
     * 서버가 Content-Length 를 제공하지 않을 때는 0 으로 유지 → UI 는 indeterminate 표시.
     */
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    // replay=0 — 화면이 collect 시작 전 발생한 이벤트는 의도적으로 버린다 (재진입 시 재방출 X).
    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    private var downloadJob: Job? = null

    init {
        // Settings 화면 진입 시점에 자동 다운로드 가능 조건이면 즉시 시작.
        // (Application.onCreate 에서도 한 번 시도하지만, 사용자가 직접 Settings 를
        // 연 경우에는 화면 내 진행률을 바로 볼 수 있도록 여기서도 트리거.)
        viewModelScope.launch {
            autoStartIfReady()
        }
    }

    fun onThemeSelected(mode: ThemeMode) {
        viewModelScope.launch { repository.setTheme(mode) }
    }

    fun onFontSizeSelected(size: FontSize) {
        viewModelScope.launch { repository.setCodeFontSize(size) }
    }

    fun onGridToggled(visible: Boolean) {
        viewModelScope.launch { repository.setCameraGridVisible(visible) }
    }

    /**
     * 화면 진입/재진입 시 호출 — 모델 파일이 삭제됐거나 다른 경로로 변경됐을 수 있으므로
     * 실제 파일 존재 여부를 다시 읽어 UI 와 동기화한다.
     */
    fun refreshModelStatus() {
        _modelInstalled.value = LiteRtLmModelInstaller.isInstalled(context)
    }

    /**
     * 사용자가 SAF 로 선택한 `.litertlm` Uri 를 앱 내부 저장소로 복사.
     * 3.4GB 파일이라 IO 스레드에서 처리. 자동 다운로드와 동시 진행 금지.
     */
    fun onModelSelected(uri: Uri) {
        if (downloadJob?.isActive == true) {
            Timber.tag(TAG).w("자동 다운로드 진행 중 — SAF 복사를 건너뜀")
            return
        }
        viewModelScope.launch {
            _modelInstalling.value = true
            val installed = withContext(Dispatchers.IO) {
                LiteRtLmModelInstaller.installFromUri(context, uri) != null
            }
            _modelInstalled.value = LiteRtLmModelInstaller.isInstalled(context)
            _modelInstalling.value = false
            _events.emit(
                if (installed) SettingsEvent.ModelInstalled
                else SettingsEvent.ModelInstallFailed(reason = "SAF 복사 실패")
            )
        }
    }

    /** URL 입력 저장 — 저장 후 자동 다운로드 조건 만족이면 즉시 시작. */
    fun onGemmaUrlChanged(url: String) {
        viewModelScope.launch {
            repository.setGemmaModelUrl(url)
            autoStartIfReady()
        }
    }

    /** 선택적 SHA-256 입력 저장. */
    fun onGemmaShaChanged(sha: String) {
        viewModelScope.launch { repository.setGemmaModelSha256(sha) }
    }

    /** Gemma 라이선스 수락 체크박스. 수락 후 자동 다운로드 트리거. */
    fun onLicenseToggled(accepted: Boolean) {
        viewModelScope.launch {
            repository.setGemmaLicenseAccepted(accepted)
            if (accepted) autoStartIfReady()
        }
    }

    /** UI "다운로드 시작" 버튼 — 사용자가 명시적으로 누르는 케이스. */
    fun startDownload() {
        viewModelScope.launch { triggerDownload(force = true) }
    }

    /** 진행 중인 다운로드 취소. */
    fun cancelDownload() {
        val job = downloadJob
        if (job != null && job.isActive) {
            job.cancel()
            _downloadProgress.value = null
            Timber.tag(TAG).i("사용자 취소로 다운로드 중단")
        }
    }

    /** 기존 모델 삭제 후 재다운로드. */
    fun redownloadModel() {
        viewModelScope.launch {
            modelManager.uninstall(context)
            _modelInstalled.value = false
            triggerDownload(force = true)
        }
    }

    /* ---------- 내부 ---------- */

    private suspend fun autoStartIfReady() {
        // 이미 설치됐거나, URL 이 비어있거나, 라이선스 미수락이면 스킵.
        if (modelManager.isInstalled(context)) return
        val url = repository.gemmaModelUrl.first()
        val licensed = repository.gemmaLicenseAccepted.first()
        if (url.isBlank() || !licensed) return
        triggerDownload(force = false)
    }

    private fun triggerDownload(force: Boolean) {
        // 중복 실행 방지 — 이미 활성 Job 이 있으면 skip.
        if (downloadJob?.isActive == true) {
            Timber.tag(TAG).d("이미 다운로드 진행 중 — 중복 요청 무시")
            return
        }
        downloadJob = viewModelScope.launch {
            val url = repository.gemmaModelUrl.first()
            val sha = repository.gemmaModelSha256.first().ifBlank { null }
            val licensed = repository.gemmaLicenseAccepted.first()
            if (url.isBlank()) {
                _events.emit(SettingsEvent.ModelInstallFailed("URL 이 비어 있습니다"))
                return@launch
            }
            if (!licensed) {
                _events.emit(SettingsEvent.ModelInstallFailed("Gemma 라이선스 동의가 필요합니다"))
                return@launch
            }
            _downloadProgress.value = 0f
            val result = modelManager.ensureModel(url, sha) { downloaded, total ->
                _downloadProgress.value = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
            }
            _downloadProgress.value = null
            _modelInstalled.value = modelManager.isInstalled(context)
            result.onSuccess {
                _events.emit(SettingsEvent.ModelInstalled)
            }.onFailure { err ->
                Timber.tag(TAG).e(err, "자동 다운로드 실패 (force=%b)", force)
                _events.emit(SettingsEvent.ModelInstallFailed(err.message ?: "알 수 없는 오류"))
            }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}

/**
 * 설정 화면이 한 번만 처리해야 하는 일회성 이벤트 (스낵바 등).
 * StateFlow 가 아닌 SharedFlow 로 노출 → 화면 회전 시 재방출 방지.
 */
sealed interface SettingsEvent {
    /** `.litertlm` 모델을 앱 내부 저장소로 정상 복사/다운로드 완료. */
    object ModelInstalled : SettingsEvent

    /** Uri 열기 / 파일 복사 / 다운로드 중 실패. [reason] 은 사용자에게 보여줄 메시지. */
    data class ModelInstallFailed(val reason: String) : SettingsEvent
}
