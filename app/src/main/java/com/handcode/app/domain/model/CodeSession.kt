package com.handcode.app.domain.model

/**
 * 카메라 → 편집 → 결과까지 이어지는 한 번의 작업 세션.
 * PipelineStore가 StateFlow<CodeSession?>로 들고 있으며,
 * Navigation 인자 대신 이 세션을 공유해서 큰 객체(OcrResult 등)를 안전하게 전달한다.
 */
data class CodeSession(
    val id: Long,
    val ocrResult: OcrResult,
    val editedCode: String,
    val validation: ValidationResult? = null,
    val execution: CodeResult? = null,
    /**
     * DB 에 이미 저장된 기록에서 복원한 세션일 때만 설정되는 CodeRecord id.
     * - 신규 OCR → 세션 생성: null  (toCodeRecord() 가 id=0 → autoGenerate)
     * - 기존 기록에서 복원   : record.id  (toCodeRecord() 가 해당 id → REPLACE 로 덮어쓰기)
     * 이렇게 해서 같은 기록을 수정·재실행해도 DB 에 중복 레코드가 쌓이지 않게 한다.
     */
    val recordId: Long? = null,
    /**
     * F7: 촬영/갤러리에서 만들어진 원본 이미지의 로컬 파일 경로.
     * 편집 중 AppBar 의 "원본 보기" 액션이 이 경로로 OriginalImageViewer 를 띄운다.
     * DB 복원 세션이나 content Uri 기반 갤러리 픽은 null → 원본 뷰 비활성화.
     */
    val originalImagePath: String? = null
) {
    /**
     * 세션을 DB 저장용 CodeRecord로 변환한다.
     * - title: 편집된 코드의 첫 줄(비어 있으면 "제목 없음")
     * - createdAt: 세션 id를 그대로 사용 (생성 시점 타임스탬프)
     * - hasError: ValidationResult나 CodeResult 중 하나라도 실패면 true
     * - isCompleted: execution이 존재하면 true (Success/Error 모두 포함)
     */
    fun toCodeRecord(): CodeRecord {
        val firstLine = editedCode.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(MAX_TITLE_LENGTH)
            ?: DEFAULT_TITLE

        val output = when (val result = execution) {
            is CodeResult.Success -> result.output
            is CodeResult.SyntaxError -> "문법 오류: ${result.message} (라인 ${result.line})"
            is CodeResult.RuntimeError -> "실행 오류: ${result.message}"
            null -> null
        }

        val errorOccurred = validation?.isValid == false ||
            execution is CodeResult.SyntaxError ||
            execution is CodeResult.RuntimeError

        return CodeRecord(
            // recordId 가 있으면 REPLACE 로 해당 row 를 갱신, 없으면 0L → autoGenerate 로 신규 insert.
            // 이렇게 해서 "기존 기록 불러와 수정·재실행" 플로우에서 DB 중복 레코드가 쌓이지 않는다.
            id = recordId ?: 0L,
            title = firstLine,
            code = editedCode,
            createdAt = id,
            ocrConfidence = ocrResult.confidence,
            executionOutput = output,
            hasError = errorOccurred,
            isCompleted = execution != null
        )
    }

    companion object {
        private const val MAX_TITLE_LENGTH = 40
        private const val DEFAULT_TITLE = "제목 없음"
    }
}
