package com.handcode.app.domain.model

/**
 * OCR 엔진 식별자.
 *
 *  - v2: `ML_KIT` 단일.
 *  - Phase 2C: `VLM` 추가 (llama.cpp + MiniCPM-V 시도, 2026-04-19 폐기).
 *  - 2026-04-19 MediaPipe GenAI 전환: `VLM` = Gemma-3n E2B `.litertlm`.
 *    단일 엔진이지만 enum 값은 하위 호환을 위해 유지 (UI 라벨은 "Gemma-3n" 로 치환).
 *  - `ML_KIT` 는 현재 DI 바인딩 없음. OcrResult 기본값 유지용으로만 남겨둠.
 */
enum class OcrEngineType { ML_KIT, VLM }

/**
 * OCR 인식 결과.
 *
 * v2 변경:
 *  - [engineUsed] 는 단일 ML Kit 로 고정 (하위 호환을 위해 필드는 유지).
 *  - [indentLevels] 는 `IndentReconstructor` 가 재조립한 라인별 들여쓰기 레벨 (0, 1, 2…).
 *    `textBlocks` 순서와 동일. 비어 있으면 재조립을 수행하지 않았거나 단일 라인.
 */
data class OcrResult(
    val recognizedText: String,
    val confidence: Float,
    val lineCount: Int,
    val textBlocks: List<RecognizedBlock> = emptyList(),
    val engineUsed: OcrEngineType = OcrEngineType.ML_KIT,
    val indentLevels: List<Int> = emptyList()
)

/**
 * OCR이 인식한 텍스트 블록 하나. 바운딩 박스 좌표 포함.
 */
data class RecognizedBlock(
    val text: String,
    val confidence: Float,
    val top: Int = 0,
    val left: Int = 0,
    val width: Int = 0,
    val height: Int = 0
)
