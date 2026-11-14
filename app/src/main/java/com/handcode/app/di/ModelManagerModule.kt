package com.handcode.app.di

import android.content.Context
import com.handcode.app.data.ocr.mediapipe.GemmaModelManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Gemma `.litertlm` 자동 다운로드 매니저 Hilt 제공자.
 *
 * [EngineModule] 은 abstract + @Binds 전용이라 인스턴스 생성이 필요한 `GemmaModelManager` 는
 * 별도 object module 에서 `@Provides` 로 선언한다.
 *
 * - Application 스코프 Singleton — 앱 전역에서 단일 인스턴스 공유 (다운로드 Mutex 재사용 목적).
 * - Context 의존성만 있으므로 생성자 호출이 가볍다.
 */
@Module
@InstallIn(SingletonComponent::class)
object ModelManagerModule {

    @Provides
    @Singleton
    fun provideGemmaModelManager(
        @ApplicationContext context: Context,
    ): GemmaModelManager = GemmaModelManager(context)
}
