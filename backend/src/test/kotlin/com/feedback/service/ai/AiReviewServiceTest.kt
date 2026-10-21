package com.feedback.service.ai

import com.feedback.common.exception.ClientException
import com.feedback.config.RuntimeConfigService
import com.feedback.repository.ai.AiReviewFact
import com.feedback.repository.ai.AiReviewRepository
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.service.masking.MaskingPipeline
import com.feedback.service.masking.MaskingResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class AiReviewServiceTest {
    private val repository = mockk<AiReviewRepository>(relaxed = true)
    private val permissions = mockk<DataPermissionService>()
    private val masking = mockk<MaskingPipeline>()
    private val service = AiReviewService(repository, permissions, masking)

    @Test
    fun `pending keeps queue reason summary and deterministic priority`() {
        val scope = DataAccessScope(setOf("SDK"), null, null)
        every { permissions.scope() } returns scope
        every { repository.pending(scope, AiReviewService.DEFAULT_REVIEW_THRESHOLD) } returns listOf(
            fact(1, "LOW_CONFIDENCE", "S2", "0.50"), fact(2, "BOTH", "S1", "0.40"),
            fact(3, "LEGACY_PENDING", "S3", null)
        )

        val result = service.pending(1, 20)
        val records = result["records"] as List<*>

        assertEquals(3L, result["total"])
        assertEquals(2L, (records.first() as Map<*, *>)["sourceId"])
        assertEquals(1L, (result["summary"] as Map<*, *>)["both"])
    }

    @Test
    fun `pending uses governed confidence threshold in repository and response`() {
        val config=mockk<RuntimeConfigService>()
        val scope=DataAccessScope(setOf("SDK"),null,null)
        every { config.decimal(AiReviewService.REVIEW_THRESHOLD_KEY, AiReviewService.DEFAULT_REVIEW_THRESHOLD) } returns BigDecimal("0.91")
        every { permissions.scope() } returns scope
        every { repository.pending(scope, BigDecimal("0.91")) } returns emptyList()

        val result=AiReviewService(repository,permissions,masking,config).pending(1,20,"LOW_CONFIDENCE")

        assertEquals(BigDecimal("0.91"),result["threshold"])
        assertTrue(result["queueDefinition"].toString().contains("0.91"))
    }

    @Test
    fun `overrule masks comment and atomically delegates standard review and audit changes`() {
        val scope=DataAccessScope(setOf("SDK"),null,null)
        every { permissions.scope() } returns scope
        every { masking.mask("手机号 13800138000 已核实", "ai_review_7") } returns
            MaskingResult("手机号 138****8000 已核实", "PASS", listOf("PHONE"))

        service.review(7, AiReviewRequest("OVERRULE", "BUG", "S1", "手机号 13800138000 已核实"), 9)

        verify { repository.requireAccessible(7, scope) }
        verify { repository.applyReview(7,"OVERRULE","BUG","S1",9,"手机号 138****8000 已核实") }
    }

    @Test
    fun `rejects review comment that cannot pass masking gate`() {
        every { masking.mask(any(), any()) } returns MaskingResult("隔离", "REVIEW_NEEDED", emptyList())
        assertThrows(ClientException::class.java) {
            service.review(7, AiReviewRequest("CONFIRM", comment = "需要复核"), 9)
        }
    }

    @Test
    fun `batch confirm validates one scope and delegates one batch mutation`() {
        val scope=DataAccessScope(setOf("SDK"),null,null)
        every { permissions.scope() } returns scope
        every { masking.mask("批量复核证据完整", "ai_review_batch") } returns MaskingResult("批量复核证据完整", "PASS", emptyList())
        every { repository.applyBatch(any(),any(),any(),any(),any(),any()) } returns 2

        val changed = service.reviewBatch(AiBatchReviewRequest(listOf(7, 8), "CONFIRM", comment = "批量复核证据完整"), 9)

        assertEquals(2, changed)
        verify { repository.requireAccessible(listOf(7L,8L),scope) }
        verify { repository.applyBatch(listOf(7L,8L),"CONFIRM",null,null,9,"批量复核证据完整") }
    }

    @Test
    fun `batch review rejects more than 500 rows before masking`() {
        assertThrows(ClientException::class.java) {
            service.reviewBatch(AiBatchReviewRequest((1L..501L).toList(), "CONFIRM", comment = "批量审核"), 9)
        }
        verify(exactly = 0) { masking.mask(any(), any()) }
    }

    private fun fact(id:Long,reason:String,severity:String,confidence:String?)=AiReviewFact(
        id,"SDK","title",null,"BUG",severity,confidence?.let(::BigDecimal),null,null,"BUG",severity,
        LocalDateTime.of(2026,1,1,0,0).plusMinutes(id),reason
    )
}
