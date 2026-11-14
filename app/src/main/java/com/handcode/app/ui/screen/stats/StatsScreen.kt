package com.handcode.app.ui.screen.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.handcode.app.domain.model.LearningStats

private const val PERCENT_SCALE = 100
private const val CIRCULAR_SIZE_DP = 72

/**
 * 학습 통계 화면.
 *
 * 상단: 총 노트 / 평균 줄 수 (2개 카드 가로 배치) — 8주차 2차에서 "평균 정확도 %" 를 폐기하고
 *   실측 지표인 줄 수로 교체. ML Kit v2 가 신뢰도 미반환이라 기존 % 는 항상 0 이었음.
 * 중앙: 주간 막대그래프 (WeeklyBarChart)
 * 하단: 완료율 CircularProgressIndicator + 퍼센트 텍스트 (정확도와 무관 — 사용자가 "완료" 표시한 노트 비율)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val stats by viewModel.stats.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("학습 통계") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("뒤로") }
                }
            )
        }
    ) { padding ->
        StatsContent(padding = padding, stats = stats)
    }
}

@Composable
private fun StatsContent(padding: PaddingValues, stats: LearningStats?) {
    // 상태 3-way 분기를 when 으로 정리 — 기존의 중간 return 패턴 제거.
    //   null       → 로딩 중
    //   기록 0건   → 빈 상태 안내
    //   그 외      → 정상 통계
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
    ) {
        when {
            stats == null -> LoadingIndicator()
            stats.totalNotes == 0 -> EmptyStatsHint()
            else -> StatsDetail(stats = stats)
        }
    }
}

@Composable
private fun StatsDetail(stats: LearningStats) {
    SummaryRow(stats = stats)
    Spacer(modifier = Modifier.height(24.dp))

    SectionTitle("최근 7일 학습량")
    Spacer(modifier = Modifier.height(8.dp))
    WeeklyBarChart(counts = stats.weeklyCounts)
    Spacer(modifier = Modifier.height(24.dp))

    SectionTitle("완료율")
    Spacer(modifier = Modifier.height(8.dp))
    CompletionCircle(rate = stats.completionRate)
}

@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SummaryRow(stats: LearningStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(
            title = "총 노트",
            value = "${stats.totalNotes}개",
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            // 8주차 변경: "평균 정확도(%)" → "평균 줄 수" — ML Kit v2 가 신뢰도를 반환하지
            // 않아 기존 지표는 항상 0 % 를 찍었다. 실제 학습 규모를 반영하는 줄 수로 교체한다.
            title = "평균 줄 수",
            value = "${stats.averageLineCount.toInt()}줄",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium
    )
}

@Composable
private fun CompletionCircle(rate: Float) {
    val percent = (rate * PERCENT_SCALE).toInt()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { rate.coerceIn(0f, 1f) },
                modifier = Modifier.size(CIRCULAR_SIZE_DP.dp)
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium
            )
        }
        Text(
            text = "완료 표시한 노트 비율이에요",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyStatsHint() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "아직 학습 기록이 없어요",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
