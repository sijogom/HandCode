package com.handcode.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private const val DATA_STORE_NAME = "handcode_settings"

/**
 * preferencesDataStore delegate 는 top-level Context 확장으로 선언해야 한다.
 * 앱 전체에서 이 인스턴스 하나만 존재하도록 top-level 로 두는 게 공식 가이드.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DATA_STORE_NAME
)

/**
 * DataStore<Preferences> 를 Hilt Singleton 으로 제공.
 * SettingsRepository 에서 생성자 주입으로 사용한다.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.settingsDataStore
}
