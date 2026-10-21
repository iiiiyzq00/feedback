package com.feedback.service.normalization

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.audit.SystemAuditInsertPo
import com.feedback.repository.auth.SystemAuditMutationRepository
import org.junit.jupiter.api.Test

class FieldConversionHistoryServiceTest {
    @Test
    fun `persists only governed enum fields`() {
        val audits = mockk<SystemAuditMutationRepository>(relaxed = true)
        val service = FieldConversionHistoryService(audits, ObjectMapper())

        service.record(10, 20, listOf(
            FieldConversion("severity_level", "Highest", "S0", "AUTO_MAPPING", "受控枚举标准化"),
            FieldConversion("description", "不得保存的正文", null, "UNMAPPED", "非法字段")
        ), "jira-v3", "enumset-123456789abc")

        verify(exactly = 1) { audits.append(match<SystemAuditInsertPo> {
            it.operation == "FIELD_CONVERSION" && it.targetId == "10" &&
                it.operationDetailJson.contains("severity_level") && !it.operationDetailJson.contains("不得保存的正文")
        }) }
    }
}
