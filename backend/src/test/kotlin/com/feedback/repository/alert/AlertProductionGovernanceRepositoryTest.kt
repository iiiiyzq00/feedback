package com.feedback.repository.alert

import com.feedback.mapper.alert.AlertProductionAcceptanceMapper
import com.feedback.mapper.alert.AlertProductionAuditMapper
import com.feedback.mapper.alert.AlertProductionConfigMapper
import com.feedback.mapper.alert.AlertProductionRuleMapper
import com.feedback.mapper.alert.AlertProductionUserMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AlertProductionGovernanceRepositoryTest {
    private val audits=mockk<AlertProductionAuditMapper>()
    private val repository=AlertProductionGovernanceRepository(
        mockk<AlertProductionConfigMapper>(),mockk<AlertProductionRuleMapper>(),
        mockk<AlertProductionAcceptanceMapper>(),mockk<AlertProductionUserMapper>(),audits,
    )

    @Test fun `automatic disable rejects missing audit fact`() {
        every{audits.insertSystem("9","{}") } returns 0

        assertThrows(com.feedback.common.exception.ServiceException::class.java){repository.autoDisabled(9,"{}")}
    }
}
