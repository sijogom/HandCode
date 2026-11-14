package com.handcode.app.di

import com.handcode.app.data.interpreter.ChaquopyCodeExecutor
import com.handcode.app.data.ocr.mediapipe.MediaPipeGenAiEngine
import com.handcode.app.data.parser.ChaquopyCodeValidator
import com.handcode.app.domain.repository.CodeExecutor
import com.handcode.app.domain.repository.CodeValidator
import com.handcode.app.domain.repository.OcrEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 엔진 인터페이스 → 구현체 바인딩.
 *
 * 2026-04-19 재구성 (MediaPipe GenAI 전환):
 *  - `CompositeOcrEngine` (VLM + ML Kit 폴백) / `VlmOcrEngine` (llama.cpp 기반) 전부 폐기.
 *  - 단일 [MediaPipeGenAiEngine] 으로 교체 — Gemma-3n E2B `.litertlm` 멀티모달 추론.
 *  - `@Named(VLM)` / `@Named(MLKIT)` qualifier 제거. 호출처는 인터페이스만 주입받으므로 투명.
 *
 * 현재 바인딩:
 *  - OcrEngine       → MediaPipeGenAiEngine
 *  - CodeValidator   → ChaquopyCodeValidator (Python ast.parse)
 *  - CodeExecutor    → ChaquopyCodeExecutor  (Python exec + stdout 캡처)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @Singleton
    abstract fun bindOcrEngine(impl: MediaPipeGenAiEngine): OcrEngine

    @Binds
    @Singleton
    abstract fun bindCodeValidator(impl: ChaquopyCodeValidator): CodeValidator

    @Binds
    @Singleton
    abstract fun bindCodeExecutor(impl: ChaquopyCodeExecutor): CodeExecutor
}
