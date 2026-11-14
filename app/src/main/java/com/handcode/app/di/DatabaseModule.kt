package com.handcode.app.di

import android.content.Context
import androidx.room.Room
import com.handcode.app.data.local.AppDatabase
import com.handcode.app.data.local.dao.CodeRecordDao
import com.handcode.app.data.local.dao.SessionSnapshotDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room DB 와 DAO 를 Hilt 에 공급하는 모듈.
 *
 * 2026-04-19: MediaPipe GenAI 단일 엔진 전환으로 ModelInstallDao / MIGRATION_2_3 / MIGRATION_3_4
 * 를 제거. 현재는 v1→v2 (session_snapshot 테이블 신설) 마이그레이션만 유지한다.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(
            ctx,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideCodeRecordDao(db: AppDatabase): CodeRecordDao = db.codeRecordDao()

    @Provides
    fun provideSessionSnapshotDao(db: AppDatabase): SessionSnapshotDao =
        db.sessionSnapshotDao()
}
