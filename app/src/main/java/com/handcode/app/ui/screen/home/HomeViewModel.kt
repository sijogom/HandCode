package com.handcode.app.ui.screen.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handcode.app.data.ocr.mediapipe.GemmaModelManager
import com.handcode.app.data.pipeline.PipelineStore
import com.handcode.app.data.preferences.SettingsRepository
import com.handcode.app.domain.model.CodeRecord
import com.handcode.app.domain.repository.CodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val RECENT_LIMIT = 3
private const val STATE_IN_TIMEOUT_MS = 5_000L

/**
 * 홈 화면 ViewModel.
 *
 * 책임:
 *  1. 최근 기록 3건을 observe 하고, 카드 탭 시 PipelineStore 에 로드
 *  2. **모델 다운로드 게이트** — 첫 실행 시 Gemma `.litertlm` 미설치면 다이얼로그를 띄우도록
 *     [showGate] 를 true 로 시작. 사용자가 라이선스 동의 후 다운로드를 트리거하면
 *     진행률을 [downloadProgress] 로 노출.
 *
 * Settings 화면(`SettingsViewModel`) 의 다운로드 흐름은 그대로 유지 — 본 ViewModel 은
 * 첫 실행 안내 + 진행률 노출만 담당하고 같은 [GemmaModelManager] 인스턴스를 공유한다
 * (Hilt Singleton 이라 자동 공유, Mutex 도 매니저 내부에서 동시 호출 차단).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: CodeRepository,
    private val pipelineStore: PipelineStore,
    private val settingsRepository: SettingsRepository,
    private val modelManager: GemmaModelManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val recentRecords: StateFlow<List<CodeRecord>> = repository
        .observeRecords()
        .map { it.take(RECENT_LIMIT) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_IN_TIMEOUT_MS),
            initialValue = emptyList()
        )

    private val _modelInstalled = MutableStateFlow(modelManager.isInstalled(context))
    val modelInstalled: StateFlow<Boolean> = _modelInstalled.asStateFlow()

    /** Settings 와 동일한 [SettingsRepository] 키를 구독 — 한쪽에서 동의해도 다른 쪽 즉시 반영. */
    val licenseAccepted: StateFlow<Boolean> = settingsRepository.gemmaLicenseAccepted
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_IN_TIMEOUT_MS),
            initialValue = false
        )

    /** 0.0~1.0 진행률. null = 다운로드 진행 중 아님. 서버가 Content-Length 미제공이면 0 유지. */
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    /** 다운로드 실패 시 사용자에게 보여줄 메시지. 성공/재시도 시 null 로 리셋. */
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    /**
     * 첫 실행 안내 다이얼로그 표시 여부. 모델 미설치 상태에서 true 로 시작하고,
     * 사용자가 [dismissGate] 또는 다운로드 성공 시 false. 다음 진입 때도 모델이 여전히
     * 미설치면 [refreshModelStatus] 가 다시 true 로 되돌리지는 않는다 — 한번 닫은 사용자에게
     * 매번 띄우면 짜증나므로 같은 세션에서는 닫힌 상태 유지.
     */
    private val _showGate = MutableStateFlow(false)
    val showGate: StateFlow<Boolean> = _showGate.asStateFlow()

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            val installed = modelManager.isInstalled(context)
            _modelInstalled.value = installed
            // 모델 미설치 = 첫 실행 또는 사용자가 모델을 지운 케이스 → 안내 다이얼로그.
            _showGate.value = !installed
        }
    }

    /** 카드 탭 시: 해당 기록을 편집 세션으로 복원 후 콜백 실행. */
    fun openRecord(id: Long, onNavigate: () -> Unit) {
        viewModelScope.launch {
            val record = repository.findById(id) ?: return@launch
            pipelineStore.startFromExisting(record)
            onNavigate()
        }
    }

    /** 다이얼로그 [동의하고 다운로드] — 라이선스 수락 + 다운로드 시작. */
    fun acceptLicenseAndDownload() {
        viewModelScope.launch {
            settingsRepository.setGemmaLicenseAccepted(true)
            triggerDownload()
        }
    }

    /** 다이얼로그 [나중에] — 다이얼로그만 닫고 다운로드는 시작하지 않음 (사용자가 Settings 에서 수동). */
    fun dismissGate() {
        _showGate.value = false
    }

    /**
     * 진행 중인 다운로드를 사용자가 취소. 매니저 내부 Mutex 는 코루틴 cancel 시
     * try-finally 로 풀리므로 다음 호출이 정상 진입 가능.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadProgress.value = null
    }

    /** Settings 에서 다운로드/SAF 로 모델을 변경했을 수 있으니 화면 재진입 시 동기화. */
    fun refreshModelStatus() {
        viewModelScope.launch {
            val installed = modelManager.isInstalled(context)
            _modelInstalled.value = installed
            if (installed) {
                _showGate.value = false
                _downloadError.value = null
            }
        }
    }

    private fun triggerDownload() {
        if (downloadJob?.isActive == true) {
            Timber.tag(TAG).d("이미 다운로드 진행 중 — 중복 요청 무시")
            return
        }
        downloadJob = viewModelScope.launch {
            val url = settingsRepository.gemmaModelUrl.first()
            val sha = settingsRepository.gemmaModelSha256.first().ifBlank { null }
            if (url.isBlank()) {
                _downloadError.value = "다운로드 URL 이 비어 있습니다. 설정에서 URL 을 입력하세요."
                return@launch
            }
            _downloadError.value = null
            _downloadProgress.value = 0f
            val result = modelManager.ensureModel(url, sha) { downloaded, total ->
                _downloadProgress.value =
                    if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
            }
            _downloadProgress.value = null
            _modelInstalled.value = modelManager.isInstalled(context)
            result.onSuccess {
                _showGate.value = false
                Timber.tag(TAG).i("Gemma 모델 다운로드 완료 — %s", it.absolutePath)
            }.onFailure { err ->
                Timber.tag(TAG).w(err, "Gemma 모델 다운로드 실패")
                _downloadError.value = err.message ?: "다운로드에 실패했어요. 잠시 후 다시 시도해 주세요."
            }
        }
    }

    companion object {
        private const val TAG = "HomeViewModel"
    }
}
