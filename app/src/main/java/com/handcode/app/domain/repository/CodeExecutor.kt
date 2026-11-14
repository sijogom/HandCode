package com.handcode.app.domain.repository

import com.handcode.app.domain.model.CodeResult

/**
 * Python 코드를 실행하고 결과를 반환하는 인터페이스.
 * 구현체: data/interpreter/ (3주차 Chaquopy 연동)
 */
interface CodeExecutor {
    suspend fun execute(code: String): CodeResult
}
