package com.handcode.app.data.ocr.postprocess

/**
 * 편집 제안 카테고리. `PythonTokenRepair` 가 생성하는 제안 타입.
 */
enum class SuggestionCategory {
    MISSING_COLON,
    UNBALANCED_PAREN,
    UNBALANCED_QUOTE,
    KEYWORD_TYPO
}

/**
 * 편집 제안. 탭 한 번이면 해당 라인 텍스트를 [replacement] 로 교체.
 *
 * @param line 0-indexed (편집기 라인 번호 = line + 1)
 * @param category 제안 분류 — UI 에서 severity 매핑에 사용
 * @param originalLine 원본 라인 (사용자에게 diff 표시용)
 * @param replacement 교체 라인
 * @param message 한국어 설명 — QuickFixPanel 타이틀로 표시
 */
data class Suggestion(
    val line: Int,
    val category: SuggestionCategory,
    val originalLine: String,
    val replacement: String,
    val message: String
)
