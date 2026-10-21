package com.feedback.service.jira

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class JiraResolutionEvidenceMigrationTest {
    @Test
    fun `v183 adds comment summary through forward template version`() {
        val sql=Files.readString(Path.of("src/main/resources/db/migration/V183__add_jira_resolution_comment_evidence.sql"))
        assertTrue(sql.contains("'v1.4.0'"))
        assertTrue(sql.contains("$.comment_summary"))
        assertTrue(sql.contains("评论摘要"))
        assertTrue(sql.contains("version='v1.3.0'"))
    }
}
