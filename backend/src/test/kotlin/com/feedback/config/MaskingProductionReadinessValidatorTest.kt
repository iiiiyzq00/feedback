package com.feedback.config

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.repository.config.MaskingEvidenceRecord
import com.feedback.repository.config.SystemConfigRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import java.time.LocalDateTime

class MaskingProductionReadinessValidatorTest {
    private val repository = mockk<SystemConfigRepository>()
    private val validator = MaskingProductionReadinessValidator(repository)

    @Test
    fun `rejects an incomplete evidence chain`() {
        every { repository.activeMaskingRuleVersion() } returns null

        val error = assertThrows(ServiceException::class.java) {
            validator.run(DefaultApplicationArguments())
        }
        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals("PRODUCTION_MASKING_APPROVED_HISTORICAL_EVIDENCE_REQUIRED", error.reasonCode)
    }

    @Test
    fun `accepts exactly one complete active evidence chain`() {
        every { repository.activeMaskingRuleVersion() } returns evidence(
            key = "masking.rule-version",
            version = "v1",
            value = "\"release-v7\""
        )
        every { repository.maskingRelease("release-v7") } returns evidence(
            key = "MASKING_RELEASE:7",
            version = "release-v7",
            status = "PUBLISHED",
            recordVersion = 11
        )
        every { repository.maskingEvidence("MASKING_RUN:11", "MASKING_RUN") } returns evidence(
            key = "MASKING_RUN:11",
            version = "engine-v2",
            subtype = "PASSED",
            status = "APPROVED",
            recordVersion = 21,
            value = "{\"executionLogSha256\":\"${"a".repeat(64)}\",\"failureSummarySha256\":\"${"b".repeat(64)}\"}"
        )
        every { repository.maskingEvidence("MASKING_DATASET:21", "MASKING_DATASET") } returns evidence(
            key = "MASKING_DATASET:21",
            version = "dataset-v3",
            subtype = "APPROVED_HISTORICAL",
            status = "APPROVED",
            recordVersion = 100,
            effectiveTo = LocalDateTime.now().plusDays(1),
            value = "{\"boundaryCount\":20}"
        )

        validator.run(DefaultApplicationArguments())
    }

    private fun evidence(
        key: String,
        version: String,
        value: String = "{}",
        subtype: String? = null,
        status: String? = null,
        recordVersion: Long? = null,
        effectiveTo: LocalDateTime? = null
    ) = MaskingEvidenceRecord(key, version, value, subtype, status, recordVersion, effectiveTo)
}
