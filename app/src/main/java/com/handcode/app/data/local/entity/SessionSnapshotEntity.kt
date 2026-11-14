package com.handcode.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 앱이 시스템에 의해 백그라운드에서 강제 종료되는 경우를 대비해
 * PipelineStore 의 현재 세션을 Room 에 "임시 스냅샷" 형태로 남겨 두는 엔티티.
 *
 * 7주차 테크노트 #9 시나리오에서 발견된 이슈 대응:
 *   - 홈으로 나간 뒤 메모리 부족 등으로 프로세스가 죽으면
 *     Singleton 메모리 저장소인 PipelineStore 는 상태가 날아간다.
 *   - Room 에 최신 편집 상태를 선제적으로 저장해 두고, 앱 시작 시 peek 로 되살린다.
 *
 * 규칙:
 *   - DB 에 레코드는 항상 **하나만** 유지한다. PrimaryKey = 1 로 고정하고
 *     OnConflict = REPLACE 로 upsert 한다.
 *   - 정식 저장(CodeRepository.save)이 완료되면 clear() 로 즉시 지워 흔적을 남기지 않는다.
 */
@Entity(tableName = "session_snapshot")
data class SessionSnapshotEntity(
    @PrimaryKey val id: Long = SINGLE_ROW_ID,
    val codeText: String,
    val recognizedText: String,
    val confidence: Float,
    val createdAt: Long
) {
    companion object {
        /** 단일 레코드 유지를 위해 모든 insert 에서 사용할 고정 PK. */
        const val SINGLE_ROW_ID: Long = 1L
    }
}
