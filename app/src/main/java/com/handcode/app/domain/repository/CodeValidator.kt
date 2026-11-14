package com.handcode.app.domain.repository

import com.handcode.app.domain.model.ValidationResult

/**
 * Python 코드 문법을 검증하는 인터페이스.
 * 구현체: data/parser/ (3주차 Chaquopy + ast.parse() 연동)
 */
interface CodeValidator {
    suspend fun validate(code: String): ValidationResult
}
