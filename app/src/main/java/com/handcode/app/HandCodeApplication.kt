package com.handcode.app

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.handcode.app.data.ocr.mediapipe.GemmaModelManager
import com.handcode.app.data.pipeline.PipelineStore
import com.handcode.app.data.preferences.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Hilt 루트 + Chaquopy 초기화 + Timber 플랜트 + 세션 스냅샷 복원 + Gemma 자동 다운로드 트리거.
 *
 * Chaquopy 는 첫 `Python.getInstance()` 호출 전에 반드시
 * `Python.start(AndroidPlatform(this))` 가 선행되어야 한다.
 *
 * - 이미 시작된 경우 [IllegalStateException] 이 던져지므로 try/catch 로 흡수.
 * - 첫 실행 시 파이썬 표준 라이브러리 압축 해제로 수백 ms 정도 지연이 발생한다.
 *   (릴리스 빌드에서는 백그라운드 warm-up 을 고려할 여지 있음)
 *
 * 7주차 변경점:
 *   - BuildConfig.DEBUG 시 Timber.DebugTree 를 가장 먼저 plant.
 *   - Chaquopy 초기화 직후 PipelineStore 의 Room 스냅샷 복원을 백그라운드에서 시도.
 *     (EntryPoint 로 Application 컨텍스트에서 Hilt 싱글톤을 직접 꺼낸다.)
 *
 * 2026-04-19 MediaPipe GenAI 전환 변경점:
 *   - `ModelDownloadWorker` 삭제에 따라 `Configuration.Provider` 구현 제거.
 *   - `.litertlm` 모델은 Settings SAF `OpenDocument` → `LiteRtLmModelInstaller` 로 사용자 직접 복사.
 *   - WorkManager 자체가 필요 없어져 `HiltWorkerFactory` 의존성 제거.
 *
 * 2026-04-19 자동 다운로드 하이브리드 확장:
 *   - URL 이 설정되어 있고 라이선스가 수락되었으며 모델이 미설치면 앱 기동 시 백그라운드에서
 *     [GemmaModelManager.ensureModel] 를 호출. 사용자는 Settings 화면에서 진행률 확인 가능.
 *   - 실패 시 Timber 로그만 남기고 조용히 종료 — 사용자는 Settings 에서 재시도 가능.
 */
@HiltAndroidApp
class HandCodeApplication : Application() {

    /** 앱 전 생명주기 동안 살아있는 백그라운드 스코프. 스냅샷 복원 같은 단발성 작업에 사용. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        plantTimber()
        initializePython()
        restoreSessionSnapshot()
        tryAutoDownloadGemma()
    }

    private fun plantTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    private fun initializePython() {
        if (Python.isStarted()) {
            Timber.tag(TAG).d("Chaquopy 이미 시작됨 — 스킵")
            return
        }
        runCatching {
            Python.start(AndroidPlatform(this))
            Timber.tag(TAG).i("Chaquopy 초기화 완료")
        }.onFailure { throwable ->
            // 초기화 실패해도 앱은 뜨도록 하되, 이후 모든 Python 호출이 ENGINE_UNAVAILABLE 로
            // 귀결되는 근본 원인이므로 stacktrace 를 error 레벨로 남긴다.
            // `adb logcat -s HandCodeApp` 로 즉시 확인 가능.
            // 원인 후보: (1) 네이티브 .so 언팩 실패, (2) ABI 불일치, (3) 저장공간 부족.
            Timber.tag(TAG).e(throwable, "Chaquopy 초기화 실패 — 코드 실행 엔진 사용 불가")
        }
    }

    private fun restoreSessionSnapshot() {
        applicationScope.launch {
            runCatching {
                val entryPoint = EntryPointAccessors.fromApplication(
                    this@HandCodeApplication,
                    ApplicationEntryPoint::class.java
                )
                entryPoint.pipelineStore().restoreSnapshotIfAny()
            }.onFailure {
                Timber.tag(TAG).w(it, "세션 스냅샷 복원 실패")
            }
        }
    }

    /**
     * Gemma `.litertlm` 자동 다운로드 — 모든 조건이 만족될 때만 수행.
     *
     * 조건:
     *  - 모델 파일 미설치 (이미 있으면 스킵)
     *  - URL 이 placeholder 가 아니거나, 사용자가 Settings 에서 실제 URL 을 저장했음
     *  - Gemma 라이선스 수락됨
     *
     * 실패해도 앱은 정상 기동되며, 사용자는 Settings 화면에서 직접 재시도 가능.
     */
    private fun tryAutoDownloadGemma() {
        applicationScope.launch {
            runCatching {
                val entryPoint = EntryPointAccessors.fromApplication(
                    this@HandCodeApplication,
                    ApplicationEntryPoint::class.java
                )
                val manager = entryPoint.gemmaModelManager()
                if (manager.isInstalled(this@HandCodeApplication)) {
                    Timber.tag(TAG).d("Gemma 모델 이미 설치됨 — 자동 다운로드 스킵")
                    return@runCatching
                }

                val settings = entryPoint.settingsRepository()
                val url = settings.gemmaModelUrl.first()
                val sha = settings.gemmaModelSha256.first().ifBlank { null }
                val licensed = settings.gemmaLicenseAccepted.first()

                if (url.isBlank()) {
                    Timber.tag(TAG).d("Gemma URL 비어 있음 — 자동 다운로드 스킵 (SAF 경로 사용)")
                    return@runCatching
                }
                if (!licensed) {
                    Timber.tag(TAG).d("Gemma 라이선스 미수락 — 자동 다운로드 대기")
                    return@runCatching
                }

                Timber.tag(TAG).i("Gemma 자동 다운로드 시작 — url=%s", url)
                val result = manager.ensureModel(url, sha) { downloaded, total ->
                    // 진행률은 Settings ViewModel 이 별도 StateFlow 로 공유하기 때문에
                    // 여기서는 대략 10% 단위 로그만 남겨 로그 폭발을 방지.
                    if (total > 0 && downloaded % (total / 10L).coerceAtLeast(1L) < BUFFER_LOG_WINDOW) {
                        Timber.tag(TAG).d(
                            "다운로드 진행 — %.1f%%",
                            downloaded.toFloat() / total.toFloat() * 100f
                        )
                    }
                }
                result.onFailure {
                    Timber.tag(TAG).w(it, "Gemma 자동 다운로드 실패 — Settings 재시도 가능")
                }.onSuccess {
                    Timber.tag(TAG).i("Gemma 자동 다운로드 완료 — %s", it.absolutePath)
                }
            }.onFailure {
                Timber.tag(TAG).w(it, "Gemma 자동 다운로드 진입 실패")
            }
        }
    }

    /**
     * Application 컨텍스트에서 Hilt 싱글톤을 꺼내기 위한 EntryPoint.
     * ViewModel/Activity 와 달리 Application 은 @Inject 주입이 불가능하므로 이 방식을 쓴다.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ApplicationEntryPoint {
        fun pipelineStore(): PipelineStore
        fun gemmaModelManager(): GemmaModelManager
        fun settingsRepository(): SettingsRepository
    }

    companion object {
        private const val TAG = "HandCodeApp"
        private const val BUFFER_LOG_WINDOW = 16L * 1024L
    }
}
