package com.handcode.app.domain.repository

import com.handcode.app.domain.model.CodeRecord
import com.handcode.app.domain.model.SortOrder
import kotlinx.coroutines.flow.Flow

/**
 * 코드 실행 이력을 관리하는 Repository 인터페이스.
 * 구현체: data/repository/CodeRepositoryImpl (4주차 Room DB 연동)
 *
 * 4주차에 observeRecords / findById 메서드를 추가했다.
 * 6주차에 observe(query, order) / rename / setCompleted 를 추가해
 * 학습 기록 화면의 검색·정렬·부분 업데이트를 지원한다.
 */
interface CodeRepository {
    /** 모든 기록을 최신순으로 관찰한다. 홈 화면 최근 이력에서 사용. */
    fun observeRecords(): Flow<List<CodeRecord>>

    /** 단건 조회. 편집 화면에서 이전 기록을 복원할 때 사용. */
    suspend fun findById(id: Long): CodeRecord?

    /** 저장(upsert). 새 기록은 0을 기본값으로 넘기면 autoGenerate 된다. */
    suspend fun save(record: CodeRecord): Long

    /** 삭제. */
    suspend fun delete(id: Long)

    /**
     * 검색어 + 정렬 기준으로 필터링된 목록을 관찰한다.
     * query 가 빈 문자열이면 전체 목록이 대상이 된다.
     */
    fun observe(query: String, order: SortOrder): Flow<List<CodeRecord>>

    /** 제목만 변경. */
    suspend fun rename(id: Long, newTitle: String)

    /** 완료 플래그만 변경. */
    suspend fun setCompleted(id: Long, completed: Boolean)
}
