package com.feedback.service.jira

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class JiraAttachmentQueueMigrationTest {
    @Test fun `migration creates a retryable source keyed Jira attachment queue`() {
        val sql=Files.readString(Path.of("src/main/resources/db/migration/V219__queue_jira_attachment_evidence.sql"))
        assertFalse(sql.startsWith("version https://git-lfs.github.com/spec"))
        assertTrue(sql.contains("UNIQUE KEY uk_jira_attachment_source (feedback_source_id)"))
        assertTrue(sql.contains("'PENDING','PROCESSING','RETRY_PENDING','COMPLETED','FAILED'"))
        assertTrue(sql.contains("FOREIGN KEY (feedback_source_id) REFERENCES t_feedback_source(id)"))
    }

    @Test fun `backfill explicitly requeues existing Jira attachment tasks`() {
        val source=Files.readString(Path.of("src/main/kotlin/com/feedback/service/jira/JiraAttachmentPullQueue.kt"))
        val mapper=Files.readString(Path.of("src/main/resources/mybatis/mapper/jira/JiraAttachmentTaskMapper.xml"))
        val lookup=Files.readString(Path.of("src/main/resources/mybatis/mapper/jira/JiraFeedbackSourceLookupMapper.xml"))
        assertTrue(source.contains("fun enqueueBackfill"))
        assertTrue(mapper.contains("task_status='PENDING',retry_count=0"))
        assertTrue(lookup.contains("source_channel='JIRA' AND source_record_id=#{jiraKey}"))
    }
}
