package com.feedback.service.normalization

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnumMappingRegistryTest {
    private val registry = EnumMappingRegistry()

    @Test
    fun `normalizes jira raw enums into semantic fields`() {
        val raw = mapOf("problem_type" to "Bug", "priority" to "Highest", "status" to "In Progress")

        val result = registry.normalize("JIRA", raw::get)

        assertEquals("BUG", result.issueCategory)
        assertEquals("S0", result.severityLevel)
        assertEquals("IN_PROGRESS", result.processingStatus)
        assertTrue(result.errorCodes.isEmpty())
        assertTrue(result.conversions.any { it.fieldName == "severity_level" && it.oldValue == "Highest" && it.newValue == "S0" })
        assertTrue(result.conversions.any { it.fieldName == "issue_category" && it.oldValue == "Bug" && it.newValue == "BUG" })
    }

    @Test
    fun `snapshot version is deterministic and drives replay`() {
        val first = registry.snapshot("JIRA")
        val second = registry.snapshot("JIRA")

        assertEquals(first, second)
        assertTrue(first.version.matches(Regex("enumset-[0-9a-f]{12}")))
        assertEquals("S0", registry.normalize("JIRA", mapOf("priority" to "Highest")::get, first).severityLevel)
    }

    @Test
    fun `normalizes quality and redblack channel vocabularies`() {
        val quality = registry.normalize("QUALITY", mapOf("application_type" to "投诉")::get)
        val redblack = registry.normalize("REDBLACK", mapOf("emotion" to "中性")::get)

        assertEquals("COMPLAINT", quality.issueCategory)
        assertEquals("NEUTRAL", redblack.userSentiment)
    }

    @Test
    fun `accepts every canonical domain enum`() {
        val raw = mapOf(
            "function_module" to "WRONG_QUESTION_BOOK", "standard_problem_type" to "PERMISSION_DENIED",
            "severity_level" to "S3", "issue_category" to "HARDWARE", "processing_status" to "CLOSED",
            "user_sentiment" to "POSITIVE"
        )

        val result = registry.normalize("QUALITY", raw::get)

        assertEquals("WRONG_QUESTION_BOOK", result.functionModule)
        assertEquals("PERMISSION_DENIED", result.problemType)
        assertEquals("S3", result.severityLevel)
        assertEquals("HARDWARE", result.issueCategory)
        assertEquals("CLOSED", result.processingStatus)
        assertEquals("POSITIVE", result.userSentiment)
        assertTrue(result.errorCodes.isEmpty())
    }

    @Test
    fun `normalizes health and drawing product modules`() {
        val health = registry.normalize("SDK", mapOf("function_module" to "健康总览")::get)
        val drawing = registry.normalize("SDK", mapOf("function_module" to "DRAWING_CANVAS")::get)

        assertEquals("HEALTH_OVERVIEW", health.functionModule)
        assertEquals("DRAWING_CANVAS", drawing.functionModule)
        assertTrue(health.errorCodes.isEmpty())
        assertTrue(drawing.errorCodes.isEmpty())
    }

    @Test
    fun `unknown enum is not fabricated and emits safe error code`() {
        val result = registry.normalize("JIRA", mapOf("priority" to "超超级紧急")::get)

        assertNull(result.severityLevel)
        assertEquals(listOf("UNMAPPED_PRIORITY"), result.errorCodes)
        assertEquals("UNMAPPED", result.conversions.single().changeType)
    }

    @Test
    fun `uncontrolled source label is not promoted to a formal function module`() {
        val result = registry.normalize("JIRA", mapOf("function_module" to "系统相关")::get)

        assertNull(result.functionModule)
        assertTrue("UNMAPPED_FUNCTION_MODULE" in result.errorCodes)
    }
}
