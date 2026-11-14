package com.handcode.app.ui.screen.pipeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handcode.app.data.parser.ChaquopyUnavailableException
import com.handcode.app.data.pipeline.PipelineStore
import com.handcode.app.domain.model.CodeResult
import com.handcode.app.domain.model.CodeSession
import com.handcode.app.domain.repository.CodeRepository
import com.handcode.app.domain.usecase.ExecuteCodeUseCase
import com.handcode.app.domain.usecase.ValidateCodeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * 5주차 파이프라인 상태 머신을 구동하는 ViewModel.
 *
 * 설계 원칙:
 * - [PipelineStore.pipelineState] 가 single source of truth.
 *   본 ViewModel 은 그 값을 그대로 노출하고, mutation 도 Store 에 위임한다.
 * - 여러 화면(Editor / Result / Camera) 에서 각자 `hiltViewModel()` 로 인스턴스를 만들어도
 *   Store 는 `@Singleton` 이라 상태가 공유된다.
 * - UseCase 는 순수 함수로 다룬다. 상태 전이는 오직 이 ViewModel 안에서만 결정한다.
 * - DB 저장은 실행 결과가 확정된 "마지막"에만 수행한다. 앱 강제 종료 시 반쪽짜리 기록 방지.
 */
@HiltViewModel
class PipelineViewModel @Inject constructor(
    private val validateCode: ValidateCodeUseCase,
    private val executeCode: ExecuteCodeUseCase,
    private val repository: CodeRepository,
    private val pipelineStore: PipelineStore
) : ViewModel() {

    /** 파이프라인 현재 상태. Store 의 StateFlow 를 그대로 노출. */
    val state: StateFlow<PipelineState> = pipelineStore.pipelineState

    /** 현재 세션 (편집 화면 등에서 직접 참조). */
    val session: StateFlow<CodeSession?> = pipelineStore.current

    /**
     * 편집 화면의 [실행하기] 버튼에서 호출.
     * Validating → (문법 통과 시) Executing → Completed / Failed 로 전이.
     */
    fun onExecuteRequested() {
        viewModelScope.launch {
            val session = pipelineStore.current.value ?: return@launch

            // 1단계: 문법 검증
            pipelineStore.setPipelineState(PipelineState.Validating(session))
            val validation = runCatching { validateCode(session.editedCode) }
                .getOrElse { throwable ->
                    // Python 인터프리터 기동 실패 등 "엔진 자체"가 문제인 경우는
                    // 사용자에게 문법 오류로 오인되지 않도록 ENGINE_UNAVAILABLE 로 분기.
                    val reason = if (throwable is ChaquopyUnavailableException) {
                        Timber.tag(TAG).w(throwable, "코드 실행 엔진 사용 불가")
                        FailureReason.ENGINE_UNAVAILABLE
                    } else {
                        Timber.tag(TAG).d("검증 실패(예외): %s", throwable.message)
                        FailureReason.UNKNOWN
                    }
                    // 8주차 8차: 사용자가 화면에서 진짜 원인을 즉시 보도록 cause 요약을 첨부.
                    fail(reason, session, errorDetails = summarize(throwable))
                    return@launch
                }
            pipelineStore.attachValidation(validation)
            if (!validation.isValid) {
                Timber.tag(TAG).d("문법 오류로 편집 화면 유지")
                fail(FailureReason.SYNTAX_ERROR, session)
                return@launch
            }

            // 2단계: 실행 (5초 타임아웃). Chaquopy 스레드는 강제 종료 불가하므로
            //        코루틴 레벨에서 끊어내고 사용자에겐 TIMEOUT 으로 안내한다.
            //
            // ChaquopyUnavailableException 은 Executor 가 엔진 장애(런타임/모듈 로드 실패) 시
            // rethrow 하는 신호 타입이다. 사용자 코드 오류(RuntimeError)와 구분해서 ENGINE_UNAVAILABLE
            // 로 분기하고, 그 외 예외만 RuntimeError 로 흡수한다.
            pipelineStore.setPipelineState(PipelineState.Executing(session))
            var engineFailure: Throwable? = null
            val result = withTimeoutOrNull(EXECUTE_TIMEOUT_MS) {
                runCatching { executeCode(session.editedCode) }
                    .getOrElse { throwable ->
                        if (throwable is ChaquopyUnavailableException) {
                            Timber.tag(TAG).w(throwable, "코드 실행 엔진 사용 불가(실행 단계)")
                            engineFailure = throwable
                            // 이 자리에는 더미 값이라도 돌려줘야 withTimeoutOrNull 이 null 을 반환하지 않는다.
                            // 실제로는 engineFailure 플래그를 보고 바로 fail 처리한다.
                            CodeResult.RuntimeError(throwable.message ?: "엔진 사용 불가")
                        } else {
                            CodeResult.RuntimeError(throwable.message ?: "알 수 없는 오류")
                        }
                    }
            }

            engineFailure?.let { thrown ->
                fail(FailureReason.ENGINE_UNAVAILABLE, session, errorDetails = summarize(thrown))
                return@launch
            }

            if (result == null) {
                // 타임아웃 — attachResult 도 하지 않고 Failed 로 빠진다.
                Timber.tag(TAG).d("실행 타임아웃(%d ms)", EXECUTE_TIMEOUT_MS)
                fail(FailureReason.TIMEOUT, session)
                return@launch
            }

            // 3단계: 결과 첨부 + DB 저장 + 종료 상태 전이
            val finished = pipelineStore.attachResult(result) ?: session
            runCatching { repository.save(finished.toCodeRecord()) }
                .onFailure { Timber.tag(TAG).w(it, "DB 저장 실패") }
            // 정식 저장이 끝났으므로 임시 스냅샷은 지운다 (메모리 세션은 유지).
            pipelineStore.clearSnapshotOnly()

            val next = when (result) {
                is CodeResult.Success -> PipelineState.Completed(finished)
                is CodeResult.SyntaxError -> PipelineState.Failed(
                    FailureReason.SYNTAX_ERROR, finished
                )
                is CodeResult.RuntimeError -> PipelineState.Failed(
                    FailureReason.RUNTIME_ERROR, finished
                )
            }
            pipelineStore.setPipelineState(next)
        }
    }

    /** 편집 화면에서 텍스트 변경. */
    fun onCodeChanged(text: String) {
        pipelineStore.updateCode(text)
    }

    /**
     * Idle 로 되돌린다. 세션도 폐기.
     * "홈으로" / "다시 촬영" / OCR 실패 후 재시도 등에서 호출.
     */
    fun reset() {
        pipelineStore.clear()
    }

    /**
     * SYNTAX_ERROR / ENGINE_UNAVAILABLE / UNKNOWN 배너의 액션에서 호출.
     *
     * 함수명은 하위 호환(호출부가 EditorScreen 여러 곳) 때문에 `dismissSyntaxError` 로 유지하되,
     * 실제로는 "에디터 화면에 머무르는 세 가지 실패 상태" 를 모두 닫는 공용 핸들러다:
     *   1) SYNTAX_ERROR       — 문법 오류
     *   2) ENGINE_UNAVAILABLE — Chaquopy 엔진 로드 실패
     *   3) UNKNOWN            — 검증 단계에서 분류 불가한 예외 (ChaquopyUnavailableException 이 아닌 것)
     *
     * 배너만 닫고 세션은 유지한 채 Editing 상태로 되돌려 사용자가 코드 편집을 이어갈 수 있게 한다.
     * 실행(재시도) 은 본문 하단의 "실행하기" 버튼이 담당한다 — 배너 액션이 재실행을 트리거하면
     * 사용자가 의도하지 않은 실행이 발생해 혼란스럽다는 검증 피드백 반영.
     */
    fun dismissSyntaxError() {
        val current = state.value as? PipelineState.Failed ?: return
        val session = current.session ?: return
        val dismissible = current.reason == FailureReason.SYNTAX_ERROR ||
            current.reason == FailureReason.ENGINE_UNAVAILABLE ||
            current.reason == FailureReason.UNKNOWN
        if (dismissible) {
            pipelineStore.setPipelineState(PipelineState.Editing(session))
        }
    }

    /**
     * 결과 화면에서 "다시 편집"을 눌렀을 때 호출한다.
     * 현재 상태가 Completed 또는 Failed(RUNTIME_ERROR|TIMEOUT)면 해당 세션을 다시 Editing 으로 되돌려
     * Editor 화면에 진입해도 Result 로 튕기지 않게 한다.
     */
    fun backToEditing() {
        val snapshot = pipelineStore.pipelineState.value
        val session = when (snapshot) {
            is PipelineState.Completed -> snapshot.session
            is PipelineState.Failed -> snapshot.session
            else -> return
        } ?: return
        pipelineStore.setPipelineState(PipelineState.Editing(session))
    }

    private fun fail(
        reason: FailureReason,
        session: CodeSession?,
        errorDetails: String? = null
    ) {
        pipelineStore.setPipelineState(PipelineState.Failed(reason, session, errorDetails))
    }

    /**
     * 예외 → 사용자에게 노출할 한 줄 요약. ENGINE_UNAVAILABLE / UNKNOWN 배너의 디테일로 쓰인다.
     * cause 체인 첫 두 계층까지만 표기 (스택 전체는 logcat 으로).
     */
    private fun summarize(throwable: Throwable): String {
        val head = "${throwable.javaClass.simpleName}: ${throwable.message ?: "(메시지 없음)"}"
        val cause = throwable.cause?.let {
            " ← ${it.javaClass.simpleName}: ${it.message ?: "(메시지 없음)"}"
        }.orEmpty()
        return (head + cause).take(400)
    }

    companion object {
        // Chaquopy Python 실행 최대 허용 시간 (무한 루프 방어).
        // 8주차 8차: 5초 → 30초로 상향. Chaquopy 가 첫 모듈 로드(handcode_engine.py 의 첫 import 포함)
        // 시 수 초가 걸리는 케이스가 있어, 5초 타임아웃이 사용자에게 "코드 실행이 안 됨"으로 체감되는
        // 정체였다. 학생 코드의 무한 루프는 30초로도 충분히 끊어낸다.
        private const val EXECUTE_TIMEOUT_MS = 30_000L

        /** Timber 로그 태그 — Logcat 에서 "Pipeline" 으로 필터링 가능. */
        private const val TAG = "Pipeline"
    }
}
