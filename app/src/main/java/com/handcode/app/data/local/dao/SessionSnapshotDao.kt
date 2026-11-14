package com.handcode.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.handcode.app.data.local.entity.SessionSnapshotEntity

/**
 * 세션 스냅샷 접근 DAO.
 *
 * 저장은 항상 [SessionSnapshotEntity.SINGLE_ROW_ID] 로 덮어쓰기 (onConflict = REPLACE) 되므로
 * 테이블에는 최대 1개의 행만 존재한다.
 */
@Dao
interface SessionSnapshotDao {

    /** 저장된 스냅샷 1건 조회. 없으면 null. */
    @Query("SELECT * FROM session_snapshot LIMIT 1")
    suspend fun peek(): SessionSnapshotEntity?

    /** 현재 세션 스냅샷 덮어쓰기. PK 가 고정이므로 항상 단일 레코드 유지. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(snap: SessionSnapshotEntity)

    /** 정식 저장 완료 시 혹은 reset 시 호출. */
    @Query("DELETE FROM session_snapshot")
    suspend fun clear()
}
