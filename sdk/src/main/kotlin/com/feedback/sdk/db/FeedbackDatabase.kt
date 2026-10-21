package com.feedback.sdk.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.feedback.sdk.db.dao.AttachmentDao
import com.feedback.sdk.db.dao.FeedbackDao
import com.feedback.sdk.db.entity.AttachmentRecord
import com.feedback.sdk.db.entity.PendingFeedback

@Database(
    entities = [
        PendingFeedback::class,
        AttachmentRecord::class
    ],
    version = 3,
    exportSchema = false
)
abstract class FeedbackDatabase : RoomDatabase() {

    abstract fun feedbackDao(): FeedbackDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        @Volatile
        private var INSTANCE: FeedbackDatabase? = null

        fun getInstance(context: Context): FeedbackDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FeedbackDatabase::class.java,
                    "feedback_sdk.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }

        /**
         * SDK v1 的附件用的是旧版 FBA1 信封，它不持久化「仅附件」的封装密钥。
         * 已完成的历史予以保留，但对再也无法满足 FBA2 服务端契约的旧任务
         * 一律失败关闭。这样既避免了破坏性升级，
         * 也避免了 HTTP 400 的无限重试。
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attachment_record_new` (
                        `clientAttachmentId` TEXT NOT NULL,
                        `clientFeedbackId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `sha256` BLOB,
                        `encryptedDataKey` BLOB,
                        `algorithmVersion` TEXT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `parentAttachmentId` TEXT,
                        `annotationJson` TEXT,
                        `status` TEXT NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`clientAttachmentId`),
                        FOREIGN KEY(`clientFeedbackId`) REFERENCES `pending_feedback`(`clientFeedbackId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `attachment_record_new` (
                        `clientAttachmentId`, `clientFeedbackId`, `type`, `mimeType`,
                        `sizeBytes`, `sha256`, `encryptedDataKey`, `algorithmVersion`,
                        `localPath`, `parentAttachmentId`, `annotationJson`, `status`,
                        `retryCount`, `createdAt`
                    )
                    SELECT
                        `clientAttachmentId`, `clientFeedbackId`, `type`, `mimeType`,
                        `sizeBytes`, `sha256`, NULL, 'FBA1-LEGACY',
                        `localPath`, `parentAttachmentId`, `annotationJson`,
                        CASE WHEN `status` IN ('PENDING', 'UPLOADING') THEN 'FAILED' ELSE `status` END,
                        CASE WHEN `status` IN ('PENDING', 'UPLOADING') THEN 8 ELSE `retryCount` END,
                        `createdAt`
                    FROM `attachment_record`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `attachment_record`")
                db.execSQL("ALTER TABLE `attachment_record_new` RENAME TO `attachment_record`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attachment_record_clientFeedbackId` " +
                        "ON `attachment_record` (`clientFeedbackId`)"
                )
                db.execSQL(
                    """
                    UPDATE `pending_feedback`
                    SET `status` = 'PERMANENT_FAILED',
                        `lastErrorCode` = 'LEGACY_TRANSPORT_KEY_UNAVAILABLE'
                    WHERE `status` IN ('PENDING_BODY', 'PENDING_ATTACHMENTS')
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE pending_feedback ADD COLUMN transportKeyId TEXT NOT NULL DEFAULT 'sdk-transport-v2'"
                )
            }
        }
    }
}
