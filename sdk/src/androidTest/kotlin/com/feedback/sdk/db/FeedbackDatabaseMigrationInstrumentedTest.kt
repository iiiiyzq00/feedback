package com.feedback.sdk.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedbackDatabaseMigrationInstrumentedTest {
    private lateinit var context: Context
    private val databaseName = "feedback-sdk-v1-migration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun v1UpgradePreservesHistoryAndFailsClosedForUnrecoverableLegacyQueue() {
        createVersionOneDatabase()

        val migrated = Room.databaseBuilder(context, FeedbackDatabase::class.java, databaseName)
            .addMigrations(FeedbackDatabase.MIGRATION_1_2, FeedbackDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val db = migrated.openHelper.writableDatabase

        db.query(
            "SELECT status, lastErrorCode, transportKeyId FROM pending_feedback " +
                "WHERE clientFeedbackId = 'legacy-pending'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("PERMANENT_FAILED", cursor.getString(0))
            assertEquals("LEGACY_TRANSPORT_KEY_UNAVAILABLE", cursor.getString(1))
            assertEquals("sdk-transport-v2", cursor.getString(2))
        }
        db.query(
            "SELECT status, retryCount, encryptedDataKey, algorithmVersion FROM attachment_record " +
                "WHERE clientAttachmentId = 'legacy-pending-attachment'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("FAILED", cursor.getString(0))
            assertEquals(8, cursor.getInt(1))
            assertNull(cursor.getBlob(2))
            assertEquals("FBA1-LEGACY", cursor.getString(3))
        }
        db.query(
            "SELECT status, lastErrorCode FROM pending_feedback WHERE clientFeedbackId = 'legacy-completed'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("COMPLETED", cursor.getString(0))
            assertNull(cursor.getString(1))
        }
        db.query(
            "SELECT status, retryCount, algorithmVersion FROM attachment_record " +
                "WHERE clientAttachmentId = 'legacy-uploaded-attachment'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("UPLOADED", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals("FBA1-LEGACY", cursor.getString(2))
        }

        migrated.close()
    }

    private fun createVersionOneDatabase() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(V1_PENDING_FEEDBACK)
                    db.execSQL(V1_ATTACHMENT_RECORD)
                    db.execSQL(
                        "CREATE INDEX `index_attachment_record_clientFeedbackId` " +
                            "ON `attachment_record` (`clientFeedbackId`)"
                    )
                    insertFeedback(db, "legacy-pending", "PENDING_ATTACHMENTS")
                    insertFeedback(db, "legacy-completed", "COMPLETED")
                    insertAttachment(db, "legacy-pending-attachment", "legacy-pending", "PENDING", 2)
                    insertAttachment(db, "legacy-uploaded-attachment", "legacy-completed", "UPLOADED", 0)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            helper.writableDatabase
        }
    }

    private fun insertFeedback(db: SupportSQLiteDatabase, id: String, status: String) {
        db.execSQL(
            """
            INSERT INTO pending_feedback (
                clientFeedbackId, packageName, appVersion, functionModule, issueType,
                sourceEventTime, osVersion, networkType, triggerType, encryptedDataKey,
                payloadNonce, payloadCiphertext, payloadTag, contactAllowed, contactNonce,
                contactCiphertext, contactTag, status, retryCount, lastErrorCode, receiptId,
                createdAt, updatedAt
            ) VALUES (?, 'com.feedback.test', '1.0', 'TEST', 'BUG', 1, 'test', 'WIFI',
                'CODE', X'01', X'02', X'03', X'04', 0, NULL, NULL, NULL, ?, 0, NULL,
                'receipt', 1, 1)
            """.trimIndent(),
            arrayOf<Any>(id, status)
        )
    }

    private fun insertAttachment(
        db: SupportSQLiteDatabase,
        id: String,
        feedbackId: String,
        status: String,
        retryCount: Int
    ) {
        db.execSQL(
            """
            INSERT INTO attachment_record (
                clientAttachmentId, clientFeedbackId, type, mimeType, sizeBytes, sha256,
                localPath, parentAttachmentId, annotationJson, status, retryCount, createdAt
            ) VALUES (?, ?, 'SCREENSHOT', 'image/jpeg', 4, X'01', '/tmp/legacy', NULL,
                NULL, ?, ?, 1)
            """.trimIndent(),
            arrayOf<Any>(id, feedbackId, status, retryCount)
        )
    }

    companion object {
        private val V1_PENDING_FEEDBACK =
            """
            CREATE TABLE IF NOT EXISTS `pending_feedback` (
                `clientFeedbackId` TEXT NOT NULL,
                `packageName` TEXT NOT NULL,
                `appVersion` TEXT,
                `functionModule` TEXT,
                `issueType` TEXT,
                `sourceEventTime` INTEGER NOT NULL,
                `osVersion` TEXT,
                `networkType` TEXT,
                `triggerType` TEXT,
                `encryptedDataKey` BLOB,
                `payloadNonce` BLOB,
                `payloadCiphertext` BLOB,
                `payloadTag` BLOB,
                `contactAllowed` INTEGER NOT NULL,
                `contactNonce` BLOB,
                `contactCiphertext` BLOB,
                `contactTag` BLOB,
                `status` TEXT NOT NULL,
                `retryCount` INTEGER NOT NULL,
                `lastErrorCode` TEXT,
                `receiptId` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`clientFeedbackId`)
            )
            """.trimIndent()

        private val V1_ATTACHMENT_RECORD =
            """
            CREATE TABLE IF NOT EXISTS `attachment_record` (
                `clientAttachmentId` TEXT NOT NULL,
                `clientFeedbackId` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `sha256` BLOB,
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
    }
}
