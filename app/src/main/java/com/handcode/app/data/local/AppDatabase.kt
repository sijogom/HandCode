package com.handcode.app.data.local

// 7주차에 session_snapshot 테이블이 추가되며 version 이 2 로 올라갔다.
// 2026-04-19: MediaPipe GenAI 단일 엔진 전환으로 model_install 테이블(버전 3~4) 삭제.
//             신규 설치에 한해서는 v2 스키마만 의미가 있다. 단, 이미 v3/v4 로 올라간
//             디바이스가 재설치 없이 다운그레이드될 가능성은 없으므로 별도 DOWNGRADE
//             migration 은 두지 않는다 (fallbackToDestructiveMigration 도 사용 안 함).

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.handcode.app.data.local.dao.CodeRecordDao
import com.handcode.app.data.local.dao.SessionSnapshotDao
import com.handcode.app.data.local.entity.CodeRecordEntity
import com.handcode.app.data.local.entity.SessionSnapshotEntity

/**
 * HandCode 앱의 Room 데이터베이스.
 *
 * - code_records     : 사용자 학습 기록 (6주차부터 사용)
 * - session_snapshot : 앱 강제 종료 복구용 임시 스냅샷 (7주차 추가)
 */
@Database(
    entities = [
        CodeRecordEntity::class,
        SessionSnapshotEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun codeRecordDao(): CodeRecordDao

    abstract fun sessionSnapshotDao(): SessionSnapshotDao

    companion object {
        const val DATABASE_NAME = "handcode.db"

        /**
         * v1 -> v2: session_snapshot 테이블 신설.
         * code_records 는 v1 과 동일하게 유지되므로 건드리지 않는다.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_snapshot` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`codeText` TEXT NOT NULL, " +
                        "`recognizedText` TEXT NOT NULL, " +
                        "`confidence` REAL NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }
    }
}
