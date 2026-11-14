package com.handcode.app.domain.model

/**
 * 학습 기록 목록에서 사용하는 정렬 기준.
 *
 * Room Dao 의 CASE WHEN 쿼리에 문자열로 전달되므로 enum name 이 바로 sortBy 키가 된다.
 * (변경 시 CodeRecordDao.observeFiltered 쿼리의 비교 문자열도 함께 바꿔야 함)
 */
enum class SortOrder {
    /** 최신순 — createdAt DESC */
    DATE,

    /**
     * 인식 줄 수 많은 순 — code 의 개행 개수 DESC.
     *
     * 8주차 변경: 기존 ACCURACY(ocrConfidence DESC)는 ML Kit v2 가 신뢰도를 반환하지 않아
     * 항상 0 이 저장돼 정렬이 무의미해졌다. 대신 "인식된 줄 수" 로 재정의해 의미를 살린다.
     */
    LINE_COUNT,

    /** 제목 가나다순 — title ASC */
    TITLE
}
