package com.handcode.app.data.local.mapper

import com.handcode.app.data.local.entity.CodeRecordEntity
import com.handcode.app.domain.model.CodeRecord

/**
 * Domain 모델 ↔ Room 엔티티 변환.
 * Domain이 순수 Kotlin을 유지해야 하므로 양방향 변환 함수를 여기에 둔다.
 */

fun CodeRecordEntity.toDomain(): CodeRecord = CodeRecord(
    id = id,
    title = title,
    code = code,
    createdAt = createdAt,
    ocrConfidence = ocrConfidence,
    executionOutput = executionOutput,
    hasError = hasError,
    isCompleted = isCompleted
)

fun CodeRecord.toEntity(): CodeRecordEntity = CodeRecordEntity(
    id = id,
    title = title,
    code = code,
    createdAt = createdAt,
    ocrConfidence = ocrConfidence,
    executionOutput = executionOutput,
    hasError = hasError,
    isCompleted = isCompleted
)
