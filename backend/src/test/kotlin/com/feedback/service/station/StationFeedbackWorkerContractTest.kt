package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class StationFeedbackWorkerContractTest {
    @Test
    fun `worker uses repository lease heartbeat bounded concurrency and transactional finalization`() {
        val worker = Files.readString(Path.of("src/main/kotlin/com/feedback/manager/station/StationFeedbackWorker.kt"))
        val scheduler = Files.readString(Path.of("src/main/kotlin/com/feedback/job/station/StationFeedbackWorkerScheduler.kt"))
        val mapper = Files.readString(Path.of("src/main/resources/mybatis/mapper/station/online/StationOnlineChannelMapper.xml"))
        listOf(
            "StationOnlineChannelRepository", "withLeaseRenewal", "StationOnlineFinalizationService",
            "StationAttachmentDownloader", "StationPhoneResolver", "ErrorCode.STATION_DIRTY_MARKING_FAILED"
        ).forEach { assertTrue(worker.contains(it), it) }
        listOf("newFixedThreadPool(20)", "CompletableFuture.allOf").forEach { invariant ->
            assertTrue(scheduler.contains(invariant), invariant)
        }
        listOf(
            "FOR UPDATE SKIP LOCKED", "lease_expires_at", "RETRY_PENDING", "REVIEW_NEEDED",
            "'RECEIVED','RETRY_PENDING','PROCESSING'", "source_phone_ciphertext=NULL", "pending_photo_urls=NULL"
        ).forEach { assertTrue(mapper.contains(it), it) }
        assertFalse(worker.contains("JdbcTemplate"))
        assertFalse(worker.contains("Map<String, Any?>"))
    }
}
