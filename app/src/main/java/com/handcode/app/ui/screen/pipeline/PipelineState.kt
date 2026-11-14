package com.handcode.app.ui.screen.pipeline

import com.handcode.app.domain.model.CodeSession

/**
 * 5주차 파이프라인 상태 머신.
 *
 * 화면은 이 상태만 보고 그린다. 상태 전이는 PipelineViewModel 안에서만 일어나며,
 * 실제 값은 PipelineStore.pipelineState 가 single source of truth 로 보관한다.
 *
 * 전이 흐름:
 *  Idle -> Capturing -> Recognizing -> Editing -> Validating -> Executing -> Completed
 *  각 단계에서 Failed(reason, session?) 로 빠질 수 있다.
 */
sealed interface PipelineState {

    data object Idle : PipelineState

    data class Capturing(
        val message: String = "촬영 준비 중…"
    ) : PipelineState

    data class Recognizing(
        val progress: Float? = null
    ) : PipelineState

    data class Editing(
        val session: CodeSession
    ) : PipelineState

    data class Validating(
        val session: CodeSession
    ) : PipelineState

    data class Executing(
        val session: CodeSession
    ) : PipelineState

    data class Completed(
        val session: CodeSession
    ) : PipelineState

    data class Failed(
        val reason: FailureReason,
        val session: CodeSession?,
        /**
         * 사용자에게 노출할 cause 메시지(예외 클래스명 + 메시지 첫 줄).
         * 8주차 8차 도입: ENGINE_UNAVAILABLE / UNKNOWN 등에서 실제 원인을 화면에서 즉시
         * 확인할 수 있게 한다. null = 디테일 없음.
         */
        val errorDetails: String? = null
    ) : PipelineState
}

/**
 * 파이프라인 실패 원인. 각 원인별로 UI의 복구 동선이 달라진다.
 * - OCR_FAILED: 카메라 재촬영
 * - SYNTAX_ERROR: 에디터에서 코드 고치기
 * - RUNTIME_ERROR: 결과 화면에서 Python 에러 메시지 확인 후 재실행
 * - TIMEOUT: 무한 루프 등으로 5초 초과, 실행 중단 처리
 * - UNKNOWN: 위 어디에도 속하지 않는 예외
 * - ENGINE_UNAVAILABLE: Chaquopy/Python 인터프리터 자체가 기동되지 못한 상태.
 *   코드 문법 문제와 구분해야 사용자에게 "문법 오류" 로 잘못 안내하지 않는다.
 */
enum class FailureReason {
    OCR_FAILED,
    SYNTAX_ERROR,
    RUNTIME_ERROR,
    TIMEOUT,
    UNKNOWN,
    ENGINE_UNAVAILABLE
}
