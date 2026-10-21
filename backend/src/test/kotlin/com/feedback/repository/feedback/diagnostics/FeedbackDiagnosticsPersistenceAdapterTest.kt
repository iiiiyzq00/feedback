package com.feedback.repository.feedback.diagnostics

import com.feedback.application.port.feedback.diagnostics.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.feedback.diagnostics.FeedbackDiagnosticIdentityPo
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** 验证 PO 与审计 JSON 只存在于持久化适配层，应用端口保持具名事实。 */
class FeedbackDiagnosticsPersistenceAdapterTest {
    private val repository = mockk<FeedbackDiagnosticsRepository>()
    private val objectMapper = jacksonObjectMapper()
    private val adapter = FeedbackDiagnosticsPersistenceAdapter(repository, objectMapper)

    @Test
    fun `adapter maps persistence rows to named diagnostic facts`() {
        every { repository.identities(11) } returns listOf(
            FeedbackDiagnosticIdentityPo(
                sourceId = 11,
                identityType = "PHONE",
                contactCiphertext = byteArrayOf(1),
                contactNonce = byteArrayOf(2),
                contactTag = byteArrayOf(3),
                contactKeyId = "key-v1",
                contactAlgorithm = "AES-256-GCM"
            )
        )

        val fact = adapter.identities(11).single()

        assertEquals(11, fact.sourceId)
        assertEquals("PHONE", fact.identityType)
        assertEquals("key-v1", fact.keyId)
    }

    @Test
    fun `adapter serializes named audit event without leaking purpose text`() {
        val detailJson = slot<String>()
        every { repository.appendAudit(7, 11, capture(detailJson), "127.0.0.1") } just runs

        adapter.append(
            FeedbackDiagnosticsAuditEvent(
                actorId = 7,
                sourceId = 11,
                purposeCode = "DIRECT_VIEW",
                purposeSha256 = "a".repeat(64),
                purposeLength = 6,
                types = listOf("PHONE"),
                visibility = "RAW",
                ipAddress = "127.0.0.1"
            )
        )

        val json = objectMapper.readTree(detailJson.captured)
        assertEquals("DIRECT_VIEW", json["purposeCode"].asText())
        assertEquals("PHONE", json["types"].single().asText())
        assertFalse(json.has("purpose"))
    }
}
