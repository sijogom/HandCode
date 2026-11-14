package com.handcode.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room이 실제 DB 테이블로 매핑하는 엔티티.
 * Domain의 CodeRecord와 1:1 대응되며, CodeRecordMapper에서 변환한다.
 *
 * 인덱스: createdAt(최신순 조회), title(제목 검색) — 둘 다 홈/목록 화면에서 사용.
 */
@Entity(
    tableName = "code_records",
    indices = [Index("createdAt"), Index("title")]
)
data class CodeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val code: String,
    val createdAt: Long,
    val ocrConfidence: Float,
    val executionOutput: String?,
    val hasError: Boolean,
    val isCompleted: Boolean = false
)
