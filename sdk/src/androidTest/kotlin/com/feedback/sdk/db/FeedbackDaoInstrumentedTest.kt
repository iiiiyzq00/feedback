package com.feedback.sdk.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.feedback.sdk.db.entity.AttachmentRecord
import com.feedback.sdk.db.entity.PendingFeedback
import com.feedback.sdk.upload.HistoricalBodyRetry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedbackDaoInstrumentedTest {
    private lateinit var database: FeedbackDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FeedbackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun feedbackAndAttachmentsFollowQueueLifecycle() = runTest {
        val feedback = pending("feedback-1")
        database.feedbackDao().insert(feedback)
        assertEquals(1, database.feedbackDao().getPending().size)

        database.attachmentDao().insert(attachment("attachment-1", feedback.clientFeedbackId))
        assertEquals(1, database.attachmentDao().getPendingByFeedbackId(feedback.clientFeedbackId).size)
        assertEquals(1, database.attachmentDao().countIncomplete(feedback.clientFeedbackId))

        val storedAttachment = database.attachmentDao()
            .getByFeedbackId(feedback.clientFeedbackId).single()
        database.attachmentDao().update(storedAttachment.copy(status = "UPLOADED"))
        assertEquals(0, database.attachmentDao().countIncomplete(feedback.clientFeedbackId))

        val storedFeedback = database.feedbackDao().getById(feedback.clientFeedbackId)!!
        database.feedbackDao().update(storedFeedback.copy(status = "COMPLETED"))
        assertEquals("COMPLETED", database.feedbackDao().getById(feedback.clientFeedbackId)?.status)
    }

    @Test
    fun deletingFeedbackCascadesAttachments() = runTest {
        val feedback = pending("feedback-2")
        database.feedbackDao().insert(feedback)
        database.attachmentDao().insert(attachment("attachment-2", feedback.clientFeedbackId))

        database.feedbackDao().delete(feedback)

        assertNull(database.feedbackDao().getById(feedback.clientFeedbackId))
        assertEquals(0, database.attachmentDao().getByFeedbackId(feedback.clientFeedbackId).size)
    }

    @Test
    fun retryExhaustedBodyIsRecoveredFromRoomAndSchedulesUpload() = runTest {
        database.feedbackDao().insert(pending("historical-1").copy(
            status="RETRY_EXHAUSTED",retryCount=8,lastErrorCode="HTTP_500"
        ))
        var scheduled=0

        assertEquals(1,HistoricalBodyRetry(database.feedbackDao()){scheduled++}.requeue())

        val recovered=database.feedbackDao().getById("historical-1")!!
        assertEquals("PENDING_BODY",recovered.status)
        assertEquals(0,recovered.retryCount)
        assertNull(recovered.lastErrorCode)
        assertEquals(1,scheduled)
    }

    private fun pending(id: String) = PendingFeedback(
        clientFeedbackId = id,
        packageName = "com.feedback.test",
        appVersion = "1.0",
        functionModule = "AI_QA",
        issueType = "BUG",
        sourceEventTime = 1L,
        osVersion = "test",
        networkType = "WIFI",
        triggerType = "CODE",
        encryptedDataKey = byteArrayOf(1),
        payloadNonce = byteArrayOf(2),
        payloadCiphertext = byteArrayOf(3),
        payloadTag = byteArrayOf(4)
    )

    private fun attachment(id: String, feedbackId: String) = AttachmentRecord(
        clientAttachmentId = id,
        clientFeedbackId = feedbackId,
        type = "SCREENSHOT",
        mimeType = "application/octet-stream",
        sizeBytes = 4,
        sha256 = byteArrayOf(1),
        encryptedDataKey = byteArrayOf(2),
        localPath = "/tmp/encrypted",
        parentAttachmentId = null,
        annotationJson = null
    )
}
