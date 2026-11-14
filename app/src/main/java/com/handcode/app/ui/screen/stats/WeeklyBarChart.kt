package com.handcode.app.ui.screen.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Calendar

private const val CHART_HEIGHT_DP = 120
private const val LABEL_ROW_SPACING_DP = 4

// 요일 축약 — Calendar.DAY_OF_WEEK (1=일요일 … 7=토요일) 인덱스에 맞춘 배열.
private val KOREAN_WEEKDAY_SHORT = arrayOf("일", "월", "화", "수", "목", "금", "토")

/**
 * 최근 7일 일별 기록 개수 막대그래프.
 *
 * Canvas 로 직접 그린다 (외부 차트 라이브러리 추가 없이 7개 막대면 충분).
 * barWidth = 전체너비 / (막대수 * 2) → 막대 사이 간격이 막대 폭과 같아진다.
 *
 * counts 는 6일 전 → 오늘 순서로 길이 7 이어야 한다 (StatsCalculator.computeWeeklyCounts).
 * 막대 아래에 같은 순서로 요일 라벨을 표시하고, 접근성을 위해 전체 Canvas 에 contentDescription 을 부여한다.
 */
@Composable
fun WeeklyBarChart(
    counts: List<Int>,
    modifier: Modifier = Modifier
) {
    // 0 으로 나누기 방지. 모든 값이 0 이어도 높이 0 으로 그려진다.
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
    val primary = MaterialTheme.colorScheme.primary
    val weekdayLabels = rememberRecentWeekdayLabels(counts.size)
    val totalRecent = counts.sum()

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT_DP.dp)
                .semantics {
                    contentDescription = "주간 학습 추이: 최근 ${counts.size}일 동안 총 ${totalRecent}개"
                }
        ) {
            if (counts.isEmpty()) return@Canvas
            val barWidth = size.width / (counts.size * 2f)
            counts.forEachIndexed { i, value ->
                val barHeight = size.height * (value / max)
                val x = barWidth + i * barWidth * 2
                drawRect(
                    color = primary,
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight)
                )
            }
        }
        Spacer(modifier = Modifier.height(LABEL_ROW_SPACING_DP.dp))
        WeekdayLabelRow(labels = weekdayLabels)
    }
}

/**
 * 오늘 기준으로 (N-1)일 전 → 오늘 순서의 요일 약자 리스트를 만든다.
 * size 가 기본 7이 아닐 수 있는 경우도 방어 — counts 와 라벨 개수를 반드시 일치시켜야
 * 막대와 라벨 정렬이 어긋나지 않는다.
 */
@Composable
private fun rememberRecentWeekdayLabels(size: Int): List<String> {
    if (size <= 0) return emptyList()
    val calendar = Calendar.getInstance()
    // 가장 왼쪽 = (size-1)일 전, 가장 오른쪽 = 오늘.
    calendar.add(Calendar.DAY_OF_YEAR, -(size - 1))
    val labels = ArrayList<String>(size)
    repeat(size) {
        val index = calendar.get(Calendar.DAY_OF_WEEK) - 1
        labels.add(KOREAN_WEEKDAY_SHORT.getOrElse(index) { "?" })
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    return labels
}

@Composable
private fun WeekdayLabelRow(labels: List<String>) {
    if (labels.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

