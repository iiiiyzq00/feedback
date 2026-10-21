package com.feedback.service.attachment

import com.feedback.model.attachment.*

import com.feedback.dto.attachment.FeedbackAttachmentSummaryResponse

import com.feedback.model.auth.*
import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import io.swagger.v3.oas.annotations.media.Schema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeedbackAttachmentSummarySchemaContractTest {
    private val responseClass = FeedbackAttachmentSummaryResponse::class.java

    @Test
    fun `response and every public JSON field expose a reviewable generated schema contract`() {
        val responseSchema = responseClass.getAnnotation(Schema::class.java)
        assertNotNull(responseSchema)
        assertTrue(responseSchema.description.contains("附件安全摘要"))
        assertTrue(responseSchema.description.contains("DataFieldPolicy"))

        val publicFields = listOf(
            "id", "attachmentType", "mimeType", "fileSizeBytes", "width", "height", "durationSeconds",
            "uploadStatus", "securityStatus", "integrityStatus", "decryptionStatus", "malwareScanStatus",
            "contentReviewStatus", "sourceKind", "retentionUntil", "createdAt", "available", "previewStatus"
        )
        publicFields.forEach { name ->
            val schema = schema(name)
            assertTrue(schema.description.isNotBlank(), "$name must have a Chinese business description")
            assertTrue(Regex("[\\u4E00-\\u9FFF]").containsMatchIn(schema.description), "$name must describe business meaning in Chinese")
            assertTrue(schema.example.isNotBlank(), "$name must have an example")
            assertEquals(Schema.AccessMode.READ_ONLY, schema.accessMode, "$name must be documented as response-only")
        }

        assertEquals(Schema.RequiredMode.REQUIRED, schema("id").requiredMode)
        assertTrue(schema("id").description.contains("始终返回"))
        publicFields.filterNot { it == "id" }.forEach { name ->
            assertEquals(Schema.RequiredMode.NOT_REQUIRED, schema(name).requiredMode, name)
            assertTrue(schema(name).description.contains("DataFieldPolicy 拒绝时省略"), name)
        }
    }

    @Test
    fun `nullable units timezones enums and hidden policy stay explicit`() {
        val nullableFields = setOf(
            "mimeType", "fileSizeBytes", "width", "height", "durationSeconds", "uploadStatus",
            "securityStatus", "integrityStatus", "decryptionStatus", "malwareScanStatus",
            "contentReviewStatus", "sourceKind", "retentionUntil", "createdAt", "previewStatus"
        )
        nullableFields.forEach { name -> assertTrue(schema(name).nullable, "$name must document explicit null") }
        setOf("id", "attachmentType", "available").forEach { name ->
            assertFalse(schema(name).nullable, "$name is non-null when present")
        }

        assertTrue(schema("fileSizeBytes").description.contains("字节"))
        assertTrue(schema("width").description.contains("像素"))
        assertTrue(schema("height").description.contains("像素"))
        assertTrue(schema("durationSeconds").description.contains("秒"))
        setOf("retentionUntil", "createdAt").forEach { name ->
            assertEquals("date-time", schema(name).format)
            assertTrue(schema(name).description.contains("Asia/Shanghai"))
        }

        val expectedEnums = mapOf(
            "attachmentType" to setOf(
                "SCREENSHOT", "ANNOTATED_SCREENSHOT", "SCREEN_RECORDING", "IMAGE", "VIDEO", "LOG"
            ),
            "uploadStatus" to setOf("PENDING", "UPLOADED", "VERIFIED", "FAILED"),
            "securityStatus" to setOf("QUARANTINED", "VERIFIED", "REJECTED"),
            "integrityStatus" to setOf("PENDING", "VERIFIED", "FAILED"),
            "decryptionStatus" to setOf("NOT_REQUIRED", "PENDING", "DECRYPTED", "FAILED"),
            "malwareScanStatus" to setOf("PENDING", "CLEAN", "INFECTED", "ERROR"),
            "contentReviewStatus" to setOf("PENDING", "PASS", "REVIEW_NEEDED", "REJECTED"),
            "sourceKind" to setOf("SDK", "IMPORT", "STATION_FEEDBACK", "JIRA"),
            "previewStatus" to setOf("PENDING", "PROCESSING", "AVAILABLE", "FAILED")
        )
        expectedEnums.forEach { (name, values) -> assertEquals(values, schema(name).allowableValues.toSet(), name) }
        assertTrue(schema("attachmentType").description.contains("ANNOTATED_SCREENSHOT=注释截图"))
        assertTrue(schema("available").description.contains("不替代实时授权"))
        assertTrue(schema("fieldPolicy").hidden)
    }

    private fun schema(fieldName: String): Schema =
        responseClass.getDeclaredField(fieldName).getAnnotation(Schema::class.java)
            ?: error("Missing @Schema on $fieldName")
}
