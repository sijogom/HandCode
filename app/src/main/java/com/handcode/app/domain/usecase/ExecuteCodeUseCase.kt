package com.handcode.app.domain.usecase

import com.handcode.app.domain.model.CodeResult
import com.handcode.app.domain.repository.CodeExecutor
import javax.inject.Inject

/**
 * 검증 통과한 Python 코드를 실행하는 UseCase.
 * 검증 완료 코드 → Python 인터프리터 → 실행 결과 반환.
 */
class ExecuteCodeUseCase @Inject constructor(
    private val codeExecutor: CodeExecutor
) {
    suspend operator fun invoke(code: String): CodeResult {
        return codeExecutor.execute(code)
    }
}
