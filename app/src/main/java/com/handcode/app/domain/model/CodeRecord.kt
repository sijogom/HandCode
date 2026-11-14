package com.handcode.app.domain.model

/**
 * 코드 실행 이력 한 건. Room Entity와 매핑되어 로컬 DB에 저장됨.
 *
 * 4주차에서 title / ocrConfidence / hasError / isCompleted 필드가 추가되었다.
 * Domain 계층은 순수 Kotlin을 유지하기 위해 Room 어노테이션은 붙이지 않는다.
 */
data class CodeRecord(
    val id: Long = 0,
    val title: String,
    val code: String,
    val createdAt: Long = System.currentTimeMillis(),
    val ocrConfidence: Float = 0f,
    val executionOutput: String? = null,
    val hasError: Boolean = false,
    val isCompleted: Boolean = false
)
