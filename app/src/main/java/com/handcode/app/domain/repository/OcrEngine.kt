package com.handcode.app.domain.repository

import android.graphics.Bitmap
import com.handcode.app.domain.model.OcrResult

/**
 * 이미지에서 텍스트를 인식하는 OCR 엔진 인터페이스.
 * 구현체: data/ocr/ (3주차 ML Kit 연동)
 */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult
}
