package com.handcode.app.domain.model

/**
 * 학습 통계 화면에 표시하는 집계값.
 *
 * - totalNotes: 전체 학습 기록 수
 * - averageLineCount: 기록된 코드의 평균 줄 수 (0 이상 실수)
 * - completionRate: isCompleted=true 비율 (0.0 ~ 1.0)
 * - weeklyCounts: 최근 7일(6일 전 → 오늘) 일별 기록 개수. 항상 길이 7.
 *
 * 8주차 변경: `averageAccuracy` (ocrConfidence 평균) 는 ML Kit v2 가 신뢰도를 반환하지
 * 않아 항상 0 을 찍어 의미가 없었다. `averageLineCount` (코드 줄 수 평균) 로 교체해
 * 사용자에게 실제 학습 규모(평균 몇 줄짜리 코드를 작성하는가)를 보여준다.
 */
data class LearningStats(
    val totalNotes: Int,
    val averageLineCount: Float,
    val completionRate: Float,
    val weeklyCounts: List<Int>
) {
    companion object {
        private const val WEEK_DAYS = 7

        /** 빈 상태. 기록이 하나도 없을 때 기본값. */
        fun empty(): LearningStats = LearningStats(
            totalNotes = 0,
            averageLineCount = 0f,
            completionRate = 0f,
            weeklyCounts = List(WEEK_DAYS) { 0 }
        )
    }
}
