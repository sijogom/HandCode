package com.handcode.app.ui.screen.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handcode.app.domain.model.CodeRecord
import com.handcode.app.domain.model.LearningStats
import com.handcode.app.domain.repository.CodeRepository
import com.handcode.app.domain.usecase.StatsCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val STATE_IN_TIMEOUT_MS = 5_000L

/**
 * 학습 통계 화면 ViewModel.
 *
 * repository.observeRecords() 를 구독하면서 StatsCalculator 로 집계값으로 변환한다.
 * DB 가 바뀌면 (저장/삭제/완료 토글) 통계도 실시간으로 반영된다.
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    repository: CodeRepository,
    calculator: StatsCalculator
) : ViewModel() {

    /**
     * 통계 상태.
     * - `null`  → 아직 첫 collect 전 (로딩 인디케이터 표시)
     * - `empty` → 실제로 기록 0건 (빈 상태 안내)
     * - 그 외    → 정상 집계 결과
     *
     * 기존에는 `LearningStats.empty()` 를 초기값으로 두어 "로딩"과 "빈 상태"가 구분되지 않았다.
     */
    val stats: StateFlow<LearningStats?> = repository.observeRecords()
        .map<List<CodeRecord>, LearningStats?> { calculator.calculate(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_IN_TIMEOUT_MS),
            initialValue = null
        )
}
