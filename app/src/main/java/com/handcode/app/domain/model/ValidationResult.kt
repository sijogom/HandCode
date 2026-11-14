package com.handcode.app.domain.model

/**
 * Python 코드 문법 검증 결과.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<SyntaxErrorInfo> = emptyList()
)

/**
 * 문법 오류 상세 정보. OCR 인식 후 사용자에게 어디가 틀렸는지 보여줄 때 사용.
 */
data class SyntaxErrorInfo(
    val line: Int,
    val column: Int,
    val message: String
)
