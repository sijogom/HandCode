package com.handcode.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.handcode.app.data.local.entity.CodeRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 코드 기록 테이블 접근 DAO.
 * observeAll()은 Flow로 반환해 홈 화면 최근 이력이 자동으로 갱신되게 한다.
 *
 * 6주차 추가:
 *  - observeFiltered: 검색어 + 정렬 기준을 한 쿼리로 처리 (CASE WHEN)
 *  - rename / setCompleted: 학습 기록 카드의 부분 업데이트용
 */
@Dao
interface CodeRecordDao {

    @Query("SELECT * FROM code_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CodeRecordEntity>>

    @Query("SELECT * FROM code_records WHERE id = :id")
    suspend fun findById(id: Long): CodeRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: CodeRecordEntity): Long

    @Query("DELETE FROM code_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 검색 + 정렬을 한 쿼리에 담는다.
     *
     * - query 가 빈 문자열이면 전체를 대상으로 한다.
     * - sortBy 는 SortOrder.name 문자열 ("DATE" / "LINE_COUNT" / "TITLE").
     * - CASE WHEN 으로 세 정렬 기준 중 해당하는 컬럼만 실제 정렬에 기여하도록 구성.
     *
     * 8주차 변경: "ACCURACY" (ocrConfidence DESC) 는 ML Kit v2 가 신뢰도 미반환이라
     * 항상 0 이 되어 정렬이 무의미해졌다. "LINE_COUNT" 로 교체 — code 의 개행 개수 DESC.
     * `length(code) - length(replace(code, char(10), ''))` = code 에 포함된 '\n' 개수.
     * char(10) = LF. SQLite 내장 함수만 사용하므로 별도 인덱스 없이 동작한다.
     */
    @Query(
        """
        SELECT * FROM code_records
        WHERE (:query = '' OR title LIKE '%' || :query || '%'
               OR code LIKE '%' || :query || '%')
        ORDER BY
            CASE WHEN :sortBy = 'DATE' THEN createdAt END DESC,
            CASE WHEN :sortBy = 'LINE_COUNT'
                 THEN (length(code) - length(replace(code, char(10), ''))) END DESC,
            CASE WHEN :sortBy = 'TITLE' THEN title END ASC
        """
    )
    fun observeFiltered(query: String, sortBy: String): Flow<List<CodeRecordEntity>>

    @Query("UPDATE code_records SET title = :newTitle WHERE id = :id")
    suspend fun rename(id: Long, newTitle: String)

    @Query("UPDATE code_records SET isCompleted = :completed WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean)
}
