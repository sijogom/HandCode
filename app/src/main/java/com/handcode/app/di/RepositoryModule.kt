package com.handcode.app.di

import com.handcode.app.data.repository.CodeRepositoryImpl
import com.handcode.app.domain.repository.CodeRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Repository 인터페이스 → 구현체 바인딩.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCodeRepository(impl: CodeRepositoryImpl): CodeRepository
}
