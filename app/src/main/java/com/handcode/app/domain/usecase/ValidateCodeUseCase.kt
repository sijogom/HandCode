package com.handcode.app.domain.usecase

import com.handcode.app.domain.model.ValidationResult
import com.handcode.app.domain.repository.CodeValidator
import javax.inject.Inject

/**
 * Python 코드 문법을 검증하는 UseCase.
 * OCR 인식 텍스트 → 문법 검증 → 결과 반환.
 */
class ValidateCodeUseCase @Inject constructor(
    private val codeValidator: CodeValidator
) {
    suspend operator fun invoke(code: String): ValidationResult {
        return codeValidator.validate(code)
    }
}
