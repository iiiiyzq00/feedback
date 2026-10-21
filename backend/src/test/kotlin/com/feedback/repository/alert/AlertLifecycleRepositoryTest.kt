package com.feedback.repository.alert

import com.feedback.mapper.alert.AlertLifecycleCatalogMapper
import com.feedback.mapper.alert.AlertLifecycleIncidentLinkMapper
import com.feedback.mapper.alert.AlertLifecycleInstanceMapper
import com.feedback.mapper.alert.AlertLifecycleNotificationMapper
import com.feedback.mapper.alert.AlertLifecycleRuleMapper
import com.feedback.mapper.alert.AlertLifecycleUserMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AlertLifecycleRepositoryTest {
    private val instances=mockk<AlertLifecycleInstanceMapper>()
    private val repository=AlertLifecycleRepository(
        mockk<AlertLifecycleNotificationMapper>(),mockk<AlertLifecycleRuleMapper>(),
        mockk<AlertLifecycleCatalogMapper>(),instances,
        mockk<AlertLifecycleIncidentLinkMapper>(),mockk<AlertLifecycleUserMapper>(),
    )

    @Test fun `batch severity update rejects partial row count`() {
        every{instances.updateSeverity(listOf(7L,8L),"P0")} returns 1

        assertThrows(com.feedback.common.exception.ServiceException::class.java){repository.updateSeverity(listOf(7L,8L),"P0")}
    }
}
