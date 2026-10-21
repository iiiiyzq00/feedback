package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.manager.applicationidentity.*
import com.feedback.job.applicationidentity.*
import com.feedback.manager.applicationidentity.*

import com.feedback.repository.applicationidentity.*
import com.feedback.model.applicationidentity.*
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.audit.BusinessAuditService
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.repository.applicationidentity.ApplicationIdentitySourceRepository
import com.feedback.model.applicationidentity.NewApplicationIdentity
import com.feedback.repository.applicationidentity.PrimaryProblemFactRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ApplicationIdentityShadowWorkerTest {
    private val sources = mockk<ApplicationIdentitySourceRepository>()
    private val identities = mockk<ApplicationIdentityRepository>()
    private val facts = mockk<PrimaryProblemFactRepository>()
    private val audits = mockk<BusinessAuditService>()
    private val worker = ApplicationIdentityShadowWorker(sources, identities, facts, ApplicationIdentityResolver(), audits)

    @Test
    fun `same display name across SDK and station creates separate conservative identities`() {
        every { sources.nextInputs(0, 100) } returns listOf(
            input(1, ApplicationSourceChannel.SDK, ApplicationAliasType.SDK_APP_ID, "reader-sdk", "阅读器"),
            input(2, ApplicationSourceChannel.STATION_FEEDBACK, ApplicationAliasType.STATION_SOURCE_APP_ID, "reader-station", "阅读器")
        )
        every { identities.activeAliases(any()) } returns emptyMap()
        every { identities.createIdentities(any()) } answers {
            firstArg<List<NewApplicationIdentity>>().mapIndexed { index, row ->
                row.publicId to ApplicationIdentitySnapshot((index + 10).toLong(), row.publicId, row.displayName, 1)
            }.toMap()
        }
        every { identities.incrementVersions(emptyList()) } returns emptyMap()
        val bindings = slot<Map<ApplicationAliasKey, ApplicationIdentitySnapshot>>()
        every { identities.createAutoAliases(capture(bindings)) } just runs
        val rows = slot<List<PrimaryProblemFact>>()
        every { facts.saveAll(capture(rows)) } just runs
        every { audits.append(any()) } just runs

        val result = worker.process(0, 100, ApplicationIdentityShadowService.RESOLUTION_RULE_VERSION, 99, "request-1")

        assertEquals(2, result.createdIdentityCount)
        assertEquals(2, bindings.captured.values.map { it.id }.distinct().size)
        assertEquals(2, rows.captured.count {
            it.applicationResolutionStatus == ApplicationResolutionStatus.RESOLVED
        })
        verify(exactly = 1) { audits.append(match { it.requestId == "request-1" && it.actor == 99L }) }
    }

    @Test
    fun `one source whose aliases point to different identities becomes ambiguous`() {
        val input = ApplicationIdentitySourceInput(
            7,
            ApplicationSourceChannel.SDK,
            listOf(
                ApplicationAliasInput(ApplicationSourceChannel.SDK, ApplicationAliasType.SDK_APP_ID, "reader"),
                ApplicationAliasInput(ApplicationSourceChannel.SDK, ApplicationAliasType.SDK_PACKAGE_NAME, "com.example.reader")
            ),
            "reader"
        )
        val keys = input.aliases.mapNotNull(ApplicationAliasInput::normalized)
        every { sources.nextInputs(0, 10) } returns listOf(input)
        every { identities.activeAliases(any()) } returns mapOf(
            keys[0] to ActiveApplicationAlias("a1", keys[0], 1, ApplicationIdentitySnapshot(1, "i1", "a", 1)),
            keys[1] to ActiveApplicationAlias("a2", keys[1], 1, ApplicationIdentitySnapshot(2, "i2", "b", 1))
        )
        every { identities.createIdentities(emptyList()) } returns emptyMap()
        every { identities.incrementVersions(emptyList()) } returns emptyMap()
        every { identities.createAutoAliases(emptyMap()) } just runs
        val rows = slot<List<PrimaryProblemFact>>()
        every { facts.saveAll(capture(rows)) } just runs
        every { audits.append(any()) } just runs

        val result = worker.process(0, 10, ApplicationIdentityShadowService.RESOLUTION_RULE_VERSION, 99, "request-2")

        assertEquals(1, result.ambiguousCount)
        assertEquals("APPLICATION_ALIASES_MAP_TO_MULTIPLE_IDENTITIES", rows.captured.single().resolutionReasonCode)
        assertTrue(rows.captured.single().applicationIdentityId == null)
        verify(exactly = 0) { identities.createAutoAliases(match { it.isNotEmpty() }) }
    }

    @Test
    fun `missing channel fact rejects the batch before any identity fact or audit write`() {
        every { sources.nextInputs(0, 10) } throws ServiceException(
            ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED,
            "APPLICATION_SDK_MISSING"
        )

        val error = assertThrows(ServiceException::class.java) {
            worker.process(0, 10, ApplicationIdentityShadowService.RESOLUTION_RULE_VERSION, 99, "request-missing")
        }

        assertEquals(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, error.errorCode)
        assertEquals("APPLICATION_SDK_MISSING", error.reasonCode)
        verify(exactly = 0) { identities.activeAliases(any()) }
        verify(exactly = 0) { identities.createIdentities(any()) }
        verify(exactly = 0) { facts.saveAll(any()) }
        verify(exactly = 0) { audits.append(any()) }
    }

    private fun input(
        id: Long,
        channel: ApplicationSourceChannel,
        type: ApplicationAliasType,
        alias: String,
        displayName: String
    ) = ApplicationIdentitySourceInput(id, channel, listOf(ApplicationAliasInput(channel, type, alias)), displayName)
}
