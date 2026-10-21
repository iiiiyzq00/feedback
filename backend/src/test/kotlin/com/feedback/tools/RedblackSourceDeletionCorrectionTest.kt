package com.feedback.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.repository.MaskingAuditRepository
import com.feedback.service.masking.MaskingPipeline
import com.feedback.service.masking.NoopNerMasker
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class RedblackSourceDeletionCorrectionTest {
    @Test
    fun `source deletion fact does not prevent safe content recovery`() {
        val planner = RedblackSourceDeletionCorrectionPlanner(
            MaskingPipeline(mockk<MaskingAuditRepository>(), jacksonObjectMapper(), NoopNerMasker)
        )
        val input = RedblackSourceDeletionCorrectionInput(
            sourceFileHash = RedblackSourceDeletionCorrectionPlanner.EXPECTED_SOURCE_FILE_HASH,
            idHash = hash("record"),
            headline = "学习功能异常",
            content = "联系电话 13800138000，功能无法使用",
            headlineHash = hash("学习功能异常"),
            contentHash = hash("联系电话 13800138000，功能无法使用")
        )

        val result = planner.prepare(input)

        assertTrue(result.safeHeadline.isNotBlank())
        assertTrue(result.safeContent.isNotBlank())
        assertFalse(result.safeContent.contains("13800138000"))
    }

    @Test
    fun `correction command cannot update cluster link or generation state`() {
        val source = Files.readString(Path.of(
            "src/main/kotlin/com/feedback/tools/RedblackSourceDeletionCorrectionMain.kt"
        ))

        assertFalse(Regex("UPDATE\\s+t_cluster(?:_link)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(source))
        assertFalse(source.contains("clusterShadowGeneration"))
        assertEquals(
            "RESTORE_ONE_REDBLACK_SOURCE_DELETED_CONTENT_WITHOUT_CHANGING_SOURCE_FACT",
            CORRECTION_APPLY_CONFIRMATION
        )
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
