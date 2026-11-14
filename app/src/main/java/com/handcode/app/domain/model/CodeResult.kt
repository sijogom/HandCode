package com.handcode.app.domain.model

/**
 * Python 코드 검증/실행 결과를 나타내는 sealed class.
 *
 * 7주차 확장:
 *  - Success 에 stderr / executionTimeMs 필드를 추가했다.
 *    (테크노트 7주차 "Chaquopy 가 반환하는 stderr 순서 뒤집힘" 버그 대응)
 *  - 기본값을 둬서 기존 `CodeResult.Success(output)` 호출 지점은 수정 없이 동작한다.
 */
sealed class CodeResult {
    data class Success(
        val output: String,
        val stderr: String = "",
        val executionTimeMs: Long = 0L
    ) : CodeResult()

    data class SyntaxError(val line: Int, val message: String) : CodeResult()
    data class RuntimeError(val message: String) : CodeResult()
}
