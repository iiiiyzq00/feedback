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
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class StationFeedbackMigrationTest {
    @Test
    fun `V172 adds the fifth realtime channel and durable claim contract`() {
        val sql = Files.readString(Path.of("src/main/resources/db/migration/V172__station_feedback_realtime_channel.sql"))
        listOf(
            "STATION_FEEDBACK", "t_channel_station_feedback", "feedback_attributes JSON",
            "receipt_id BINARY(16)", "idx_station_claim", "source_api_version IN ('V1','V2')",
            "FOREIGN KEY (source_id) REFERENCES t_feedback_source(id)"
        ).forEach { assertTrue(sql.contains(it), it) }
    }

    @Test
    fun `V173 permits station attachments without adding another table`() {
        val sql = Files.readString(Path.of("src/main/resources/db/migration/V173__station_attachment_source_kind.sql"))
        assertTrue(sql.contains("'STATION_FEEDBACK'"))
        assertTrue(!sql.contains("CREATE TABLE"))
    }
}
