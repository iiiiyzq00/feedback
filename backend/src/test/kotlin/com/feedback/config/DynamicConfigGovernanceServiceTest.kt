package com.feedback.config

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.audit.BusinessAuditService
import com.feedback.repository.config.DynamicConfigCatalogRecord
import com.feedback.repository.config.SystemConfigRecord
import com.feedback.repository.config.SystemConfigRepository
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DynamicConfigGovernanceServiceTest {
    private val repository = mockk<SystemConfigRepository>(relaxed = true)
    private val principal = mockk<CurrentWebPrincipalProvider>()
    private val audits = mockk<BusinessAuditService>(relaxed = true)
    private val json = jacksonObjectMapper()

    @Test
    fun `mutation is disabled by default before any database write`() {
        val service = service(enabled = false)

        val error = assertThrows(ServiceException::class.java) {
            service.change(
                DynamicConfigChangeCommand(
                    listOf(DynamicConfigChangeItemCommand("sdk.rate-limit", json.readTree("120"), "v1")),
                    "提高接入容量"
                )
            )
        }

        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals("DYNAMIC_CONFIG_MUTATION_DISABLED", error.reasonCode)
        verify(exactly = 0) { repository.lockCatalogs(any()) }
    }

    @Test
    fun `current view assembles catalog and active configuration in application code`() {
        every { repository.activeCatalogs() } returns listOf(catalog("sdk.rate-limit", hotReload = true))
        every { repository.activeConfigurations(setOf("sdk.rate-limit")) } returns mapOf(
            "sdk.rate-limit" to record("sdk.rate-limit", "100", "v3")
        )

        val result = service(enabled = false).current()

        assertEquals(1, result.size)
        assertEquals("sdk.rate-limit", result.single().configKey)
        assertEquals(100, result.single().value.intValue())
        assertEquals(true, result.single().hotReload)
    }

    @Test
    fun `atomic change requires the exact active version and appends sanitized audit`() {
        val rule = catalog("sdk.rate-limit", hotReload = true)
        val audit = slot<com.feedback.audit.BusinessAuditEvent>()
        every { principal.require() } returns mockk<WebPrincipal> { every { userId } returns 9 }
        every { repository.lockCatalogs(setOf("sdk.rate-limit")) } returns mapOf("sdk.rate-limit" to rule)
        every { repository.activeConfigurations(setOf("sdk.rate-limit")) } returns mapOf(
            "sdk.rate-limit" to record("sdk.rate-limit", "100", "v3")
        )
        every { repository.historyCounts(setOf("sdk.rate-limit")) } returns mapOf("sdk.rate-limit" to 3)

        val result = service(enabled = true).change(
            DynamicConfigChangeCommand(
                listOf(DynamicConfigChangeItemCommand(" sdk.rate-limit ", json.readTree("120"), " v3 ")),
                "提高接入容量"
            )
        )

        assertEquals("v4", result.changes.single().version)
        assertEquals(true, result.hotReloaded)
        verify(exactly = 1) { repository.replaceActive(any()) }
        verify(exactly = 1) { audits.append(capture(audit)) }
        assertEquals("sdk.rate-limit", result.changes.single().key)
        assertEquals(setOf("changeSetId", "reasonSha256", "reasonLength", "changes", "sourceRecordIds"), audit.captured.detail.keys)
        assertEquals(64, audit.captured.detail["reasonSha256"].toString().length)
        assertEquals(false, audit.captured.detail.values.any { it.toString().contains("提高接入容量") })
    }

    private fun service(enabled: Boolean) = DynamicConfigGovernanceService(
        repository = repository,
        mapper = json,
        currentPrincipal = principal,
        businessAudits = audits,
        mutationEnabled = enabled
    )

    private fun catalog(key: String, hotReload: Boolean) = DynamicConfigCatalogRecord(
        configKey = key,
        valueType = "INTEGER",
        minValue = "1",
        maxValue = "1000",
        hotReload = hotReload,
        secretValue = false,
        ownerModule = "SDK",
        description = "SDK 每分钟请求上限"
    )

    private fun record(key: String, value: String, version: String) = SystemConfigRecord(
        id = 1,
        configKey = key,
        configVersion = version,
        configValue = value
    )
}
