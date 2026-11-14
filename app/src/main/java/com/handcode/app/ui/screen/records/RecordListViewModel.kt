package com.handcode.app.ui.screen.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handcode.app.data.pipeline.PipelineStore
import com.handcode.app.domain.model.CodeRecord
import com.handcode.app.domain.model.SortOrder
import com.handcode.app.domain.repository.CodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SEARCH_DEBOUNCE_MS = 300L
private const val STATE_IN_TIMEOUT_MS = 5_000L

/**
 * 학습 기록 목록 화면 ViewModel.
 *
 * - queryFlow: 검색어 입력값. 300ms debounce 로 불필요한 DB 쿼리 방지.
 * - sortOrderFlow: 정렬 토글 상태 (DATE / LINE_COUNT / TITLE).
 * - records: 두 Flow 를 combine 후 flatMapLatest 로 repository.observe 구독 전환.
 *
 * 카드 액션(rename / delete / toggleCompleted) 은 viewModelScope 에서 suspend 호출.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordListViewModel @Inject constructor(
    private val repository: CodeRepository,
    private val pipelineStore: PipelineStore
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val sortOrderFlow = MutableStateFlow(SortOrder.DATE)

    /** 화면에 바인딩되는 현재 검색어. TextField value 로 사용. */
    val query: StateFlow<String> = queryFlow.asStateFlow()

    /** 현재 선택된 정렬 토글. */
    val sortOrder: StateFlow<SortOrder> = sortOrderFlow.asStateFlow()

    val records: StateFlow<List<CodeRecord>> = combine(
        // 빈 쿼리(초기 진입 포함)는 debounce 없이 즉시 방출 — 화면 진입 직후 300ms 동안 빈 목록이
        // 깜빡이던 문제를 막는다. 실제 타이핑(비어있지 않은 쿼리)에는 SEARCH_DEBOUNCE_MS 적용.
        queryFlow.debounce { q -> if (q.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
            .distinctUntilChanged(),
        sortOrderFlow
    ) { q, order -> q to order }
        .flatMapLatest { (q, order) -> repository.observe(q, order) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_IN_TIMEOUT_MS),
            initialValue = emptyList()
        )

    fun onQueryChanged(q: String) {
        queryFlow.value = q
    }

    fun onSortChanged(order: SortOrder) {
        sortOrderFlow.value = order
    }

    fun rename(id: Long, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch { repository.rename(id, newTitle.trim()) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    /** 현재 값을 받아 반전시키는 방식. UI 쪽에서 current 를 넘겨준다. */
    fun toggleCompleted(id: Long, current: Boolean) {
        viewModelScope.launch { repository.setCompleted(id, !current) }
    }

    /**
     * 카드 탭 시: 해당 기록을 편집 세션으로 복원 후 콜백 실행.
     * HomeViewModel.openRecord 와 동일한 패턴.
     */
    fun openRecord(id: Long, onNavigate: () -> Unit) {
        viewModelScope.launch {
            val record = repository.findById(id) ?: return@launch
            pipelineStore.startFromExisting(record)
            onNavigate()
        }
    }
}
