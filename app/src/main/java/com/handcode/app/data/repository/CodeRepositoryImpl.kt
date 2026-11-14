package com.handcode.app.data.repository

import com.handcode.app.data.local.dao.CodeRecordDao
import com.handcode.app.data.local.mapper.toDomain
import com.handcode.app.data.local.mapper.toEntity
import com.handcode.app.domain.model.CodeRecord
import com.handcode.app.domain.model.SortOrder
import com.handcode.app.domain.repository.CodeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CodeRepository 구현체.
 * Room DAO에 의존하지만, 외부로는 Domain 모델(CodeRecord)만 노출한다.
 *
 * 6주차: 검색/정렬 + 부분 업데이트(rename, setCompleted) 메서드 추가.
 */
@Singleton
class CodeRepositoryImpl @Inject constructor(
    private val dao: CodeRecordDao
) : CodeRepository {

    override fun observeRecords(): Flow<List<CodeRecord>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun findById(id: Long): CodeRecord? =
        dao.findById(id)?.toDomain()

    override suspend fun save(record: CodeRecord): Long =
        dao.upsert(record.toEntity())

    override suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    override fun observe(query: String, order: SortOrder): Flow<List<CodeRecord>> =
        dao.observeFiltered(query, order.name).map { list -> list.map { it.toDomain() } }

    override suspend fun rename(id: Long, newTitle: String) {
        dao.rename(id, newTitle)
    }

    override suspend fun setCompleted(id: Long, completed: Boolean) {
        dao.setCompleted(id, completed)
    }
}
