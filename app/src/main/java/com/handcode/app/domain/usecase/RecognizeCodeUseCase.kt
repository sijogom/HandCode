package com.handcode.app.domain.usecase

import android.graphics.Bitmap
import com.handcode.app.domain.model.OcrResult
import com.handcode.app.domain.repository.OcrEngine
import javax.inject.Inject

/**
 * 이미지에서 코드를 인식하는 UseCase.
 * 카메라/갤러리 → OCR 엔진 호출 → 인식 결과 반환.
 */
class RecognizeCodeUseCase @Inject constructor(
    private val ocrEngine: OcrEngine
) {
    suspend operator fun invoke(bitmap: Bitmap): OcrResult {
        return ocrEngine.recognize(bitmap)
    }
}
