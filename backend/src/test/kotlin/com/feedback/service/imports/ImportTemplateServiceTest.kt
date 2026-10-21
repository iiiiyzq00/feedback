package com.feedback.service.imports

import com.feedback.model.imports.ImportTemplate
import com.feedback.repository.ImportTemplateRepository
import com.feedback.repository.imports.ImportTemplateConfigRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ImportTemplateServiceTest {
    private val repository = mockk<ImportTemplateRepository>()
    private val configs = mockk<ImportTemplateConfigRepository>()
    private val service = ImportTemplateService(repository, jacksonObjectMapper(), configs)

    @Test
    fun `detects strong cross-channel header mismatch`() {
        val result = service.boundary("JIRA", listOf("问题编号", "问题描述", "维修区域", "申请类型", "换货申请"))

        assertFalse(result.valid)
        assertEquals("QUALITY", result.detectedChannel)
        assertTrue(result.reason.orEmpty().startsWith("CHANNEL_HEADER_MISMATCH"))
    }

    @Test
    fun `all channels may map common application and device dimensions`() {
        listOf("JIRA", "QUALITY", "REDBLACK").forEach { channel ->
            assertTrue(service.allowedTargets(channel).containsAll(setOf("package_name", "app_version", "device_model")))
        }
    }

    @Test
    fun `exports active versioned utf8 csv contract`() {
        every { configs.active("JIRA") } returns ImportTemplate(
            id = 1L, templateName = "Jira标准导入模板", sourceChannel = "JIRA",
            version = "v1.0.0", schemaVersion = "import-contract-v1",
            headerColumns = "[\"问题编号\",\"问题描述\"]",
            fieldMappings = "{\"source_record_id\":\"问题编号\",\"description\":\"问题描述\"}",
            createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now()
        )

        val (filename, bytes) = service.csv("JIRA")

        assertEquals("jira-v1.0.0.csv", filename)
        assertTrue(bytes.toString(Charsets.UTF_8).startsWith("\uFEFF\"问题编号\",\"问题描述\""))
        val parsed = ImportFileParser().parse(filename, bytes)
        assertEquals(listOf("问题编号", "问题描述"), parsed.sheets.single().header)
    }
}
