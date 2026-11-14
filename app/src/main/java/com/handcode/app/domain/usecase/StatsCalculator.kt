package com.handcode.app.domain.usecase

import com.handcode.app.domain.model.CodeRecord
import com.handcode.app.domain.model.LearningStats
import javax.inject.Inject

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val WEEK_DAYS = 7

/**
 * 학습 기록 리스트로부터 LearningStats 를 계산한다.
 *
 * 순수 Kotlin 로직만 사용 (Android 의존 없음) → 도메인 계층 배치.
 * StatsViewModel 에서 Flow.map 으로 매번 최신 records 를 변환한다.
 */
class StatsCalculator @Inject constructor() {

    fun calculate(records: List<CodeRecord>): LearningStats {
        if (records.isEmpty()) return LearningStats.empty()

        // 8주차 변경: ocrConfidence 평균 → 코드 줄 수 평균.
        // ocrConfidence 는 ML Kit v2 에서 항상 0 이 들어오므로 평균도 0 에 고착된다.
        // 사용자에게 의미 있는 "평균 작성 줄 수" 지표로 교체한다.
        val avgLineCount = records.map { lineCountOf(it.code) }.average().toFloat()
        val completion = records.count { it.isCompleted }.toFloat() / records.size
        val weekly = computeWeeklyCounts(records)

        return LearningStats(
            totalNotes = records.size,
            averageLineCount = avgLineCount,
            completionRate = completion,
            weeklyCounts = weekly
        )
    }

    /** 코드 문자열의 줄 수. 빈 문자열은 0, 그 외는 '\n' 개수 + 1. */
    private fun lineCountOf(code: String): Int =
        if (code.isEmpty()) 0 else code.count { it == '\n' } + 1

    /**
     * 최근 7일 동안 하루별 기록 개수.
     * 인덱스 0 = 6일 전, 인덱스 6 = 오늘 (막대그래프 좌→우 시간 흐름).
     */
    private fun computeWeeklyCounts(records: List<CodeRecord>): List<Int> {
        val now = System.currentTimeMillis()
        return ((WEEK_DAYS - 1) downTo 0).map { daysAgo ->
            val dayStart = now - (daysAgo + 1) * DAY_MS
            val dayEnd = now - daysAgo * DAY_MS
            records.count { it.createdAt in dayStart..dayEnd }
        }
    }
}
