package com.feedback.service.imports

import com.feedback.common.exception.ClientException
import com.feedback.repository.attachment.FeedbackAttachmentMutationRepository
import com.feedback.repository.ImportAttachmentTaskRepository
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImportAttachmentTaskServiceTest {
    private val service = ImportAttachmentTaskService(
        mockk<ImportAttachmentTaskRepository>(), mockk<FeedbackAttachmentMutationRepository>(),
        ImportRawCrypto("import-secret-that-is-longer-than-32-characters", com.feedback.service.masking.EnvelopeCrypto(), "test")
    )

    @Test
    fun `accepts https media reference without exposing credentials`() {
        val safe = service.validateReference("https://cdn.example.com/path/demo.jpg?expires=123")
        assertEquals("cdn.example.com", safe.domain)
        assertEquals("IMAGE", safe.type)
        assertTrue(safe.canonical.startsWith("https://cdn.example.com/"))
    }

    @Test
    fun `rejects unsafe schemes credentials ip literals and non-media pages`() {
        listOf(
            "http://cdn.example.com/a.jpg",
            "file:///tmp/a.jpg",
            "https://user:pass@cdn.example.com/a.jpg",
            "https://127.0.0.1/a.jpg",
            "https://cdn.example.com/ticket/1"
        ).forEach { value -> assertThrows(ClientException::class.java) { service.validateReference(value) } }
    }
}
