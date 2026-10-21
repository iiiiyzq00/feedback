package com.feedback.controller.imports

import com.feedback.config.GlobalExceptionHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.feedback.service.imports.ImportBatchService
import com.feedback.service.imports.ImportBatchView
import com.feedback.service.imports.ImportUploadResult
import com.feedback.service.imports.ImportTemplateService
import com.feedback.service.imports.ImportChunkUploadService
import com.feedback.service.imports.ImportReconciliationExportService
import com.feedback.service.imports.ImportAttachmentUploadService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

class ImportControllerContractTest {
    private val service = mockk<ImportBatchService>()
    private val templates = mockk<ImportTemplateService>()
    private val chunks = mockk<ImportChunkUploadService>()
    private val reconciliationExports = mockk<ImportReconciliationExportService>()
    private val attachmentUploads = mockk<ImportAttachmentUploadService>()
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val mvc = MockMvcBuilders.standaloneSetup(ImportController(service, templates, chunks, reconciliationExports, attachmentUploads))
        .setControllerAdvice(GlobalExceptionHandler(SimpleMeterRegistry()))
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .build()
    private val view = ImportBatchView(
        "batch-1", "JIRA", "PROCESSING", 1, 0, 0, 0, 0,
        LocalDateTime.parse("2026-07-27T10:00:00"), null
    )

    @Test
    fun `upload requires channel and returns frontend contract`() {
        every { service.upload(any(), "JIRA", null) } returns ImportUploadResult(
            "batch-1", "AWAITING_MAPPING", "NEW", 1, listOf("Sheet1"),
            listOf("issue key", "description"), listOf(listOf("J-1", "masked")),
            mapOf("source_record_id" to "issue key", "description" to "description")
        )

        mvc.multipart("/api/v1/import/upload") {
            file("file", "issue key,description\nJ-1,text".toByteArray())
            param("source_channel", "JIRA")
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.code") { value(200) }
            jsonPath("$.data.batchId") { value("batch-1") }
            jsonPath("$.data.recommendedMapping.description") { value("description") }
        }

        mvc.multipart("/api/v1/import/upload") {
            file("file", "id,description\n1,text".toByteArray())
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `confirm sends mapping and retry reuses persisted mapping`() {
        every { service.confirm("batch-1", mapOf("source_record_id" to "id", "description" to "description")) } returns view
        every { service.retry("batch-1", null) } returns view

        mvc.post("/api/v1/import/batches/batch-1/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("mapping" to mapOf(
                "source_record_id" to "id", "description" to "description"
            )))
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.data.batchId") { value("batch-1") }
        }

        mvc.post("/api/v1/import/batches/batch-1/retry")
            .andExpect { status { isAccepted() } }
    }
}
