package com.feedback.service.knowledge

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class JiraKnowledgeCandidateMigrationTest {
    @Test
    fun `v182 adds traceable deduplicated draft candidate governance`() {
        val sql=Files.readString(Path.of("src/main/resources/db/migration/V182__jira_knowledge_candidate_governance.sql"))
        assertTrue(sql.contains("source_feedback_id"))
        assertTrue(sql.contains("candidate_fingerprint"))
        assertTrue(sql.contains("candidate_evidence JSON"))
        assertTrue(sql.contains("UNIQUE KEY uk_knowledge_source_feedback"))
        assertTrue(sql.contains("action:generate_jira_knowledge_candidates"))
        assertTrue(!sql.contains("knowledge_status='PUBLISHED'"))
    }
}
