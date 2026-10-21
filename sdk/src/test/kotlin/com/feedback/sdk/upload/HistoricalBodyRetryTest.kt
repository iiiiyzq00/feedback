package com.feedback.sdk.upload

import com.feedback.sdk.db.dao.FeedbackDao
import com.feedback.sdk.db.entity.PendingFeedback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HistoricalBodyRetryTest {
    private val dao=mockk<FeedbackDao>(relaxed=true)

    @Test fun `requeues only retry exhausted bodies and schedules one worker`()=runTest {
        val row=pending("old-1")
        coEvery { dao.getRetryExhausted(100) } returns listOf(row)
        var scheduled=0
        val count=HistoricalBodyRetry(dao){scheduled++}.requeue()
        assertEquals(1,count);assertEquals(1,scheduled)
        coVerify { dao.update(match { it.clientFeedbackId=="old-1"&&it.status=="PENDING_BODY"&&it.retryCount==0&&it.lastErrorCode==null }) }
    }

    @Test fun `empty historical queue does not schedule work`()=runTest {
        coEvery { dao.getRetryExhausted(100) } returns emptyList()
        var scheduled=0
        assertEquals(0,HistoricalBodyRetry(dao){scheduled++}.requeue())
        assertEquals(0,scheduled)
    }

    @Test fun `historical retry is bounded`() {
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { HistoricalBodyRetry(dao){}.requeue(101) } }
    }

    private fun pending(id:String)=PendingFeedback(id,"com.example","1.0",null,null,1L,null,null,"CODE",
        byteArrayOf(1),byteArrayOf(2),byteArrayOf(3),byteArrayOf(4),status="RETRY_EXHAUSTED",retryCount=8,lastErrorCode="HTTP_500")
}
