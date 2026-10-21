package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.manager.applicationidentity.*
import com.feedback.job.applicationidentity.*
import com.feedback.manager.applicationidentity.*

import com.feedback.repository.applicationidentity.*
import com.feedback.model.applicationidentity.*
import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationIdentityRebindWorkerTest {
    private val identities = mockk<ApplicationIdentityRepository>()
    private val audits = mockk<BusinessAuditService>()
    private val worker = ApplicationIdentityRebindWorker(identities, audits)

    @Test
    fun `completed request with same fingerprint replays stable result`() {
        val command = command()
        val fingerprint = fingerprint(command)
        every { identities.findManualResult(command.requestId) } returns aliasRow().copy(
            applicationIdentityId = 2,
            aliasVersion = 2,
            requestId = command.requestId,
            commandFingerprintHex = fingerprint,
            targetMappingVersionSnapshot = 4
        )

        val result = worker.rebind(command, 99)

        assertTrue(result.replayed)
        assertEquals(4, result.targetMappingVersion)
        verify(exactly = 0) { identities.lockActiveAlias(any()) }
    }

    @Test
    fun `same request id with different command is rejected`() {
        val command = command()
        every { identities.findManualResult(command.requestId) } returns aliasRow().copy(
            requestId = command.requestId,
            commandFingerprintHex = "00",
            targetMappingVersionSnapshot = 4
        )

        val error = assertThrows(ServiceException::class.java) { worker.rebind(command, 99) }
        assertEquals(ErrorCode.APPLICATION_IDENTITY_CONFLICT, error.errorCode)
    }

    @Test
    fun `successful rebind supersedes old version and stores replay snapshot`() {
        val command = command()
        val source = ApplicationIdentitySnapshot(1, "11111111-1111-4111-8111-111111111111", "source", 5)
        val target = ApplicationIdentitySnapshot(2, command.targetApplicationId, "target", 3)
        val alias = aliasRow()
        every { identities.findManualResult(command.requestId) } returns null
        every { identities.findByPublicId(command.targetApplicationId) } returns target
        every { identities.lockActiveAlias(command.aliasId) } returns alias
        every { identities.lockManualResult(command.requestId) } returns null
        every { identities.lockIdentitiesInOrder(listOf(1L, 2L)) } returns mapOf(1L to source, 2L to target)
        every { identities.incrementVersions(any()) } answers {
            val requested = firstArg<Collection<ApplicationIdentitySnapshot>>()
            assertEquals(setOf(1L, 2L), requested.map { it.id }.toSet())
            mapOf(1L to source.copy(mappingVersion = 6), 2L to target.copy(mappingVersion = 4))
        }
        every { identities.supersedeAlias(alias) } just runs
        val inserted = slot<NewApplicationIdentityAlias>()
        every { identities.insertManualAlias(capture(inserted)) } just runs
        every { audits.append(any()) } just runs

        val result = worker.rebind(command, 99)

        assertEquals(2, inserted.captured.aliasVersion)
        assertEquals(4, inserted.captured.targetMappingVersionSnapshot)
        assertEquals(ApplicationAliasEvidenceType.MANUAL_REBIND, inserted.captured.evidenceType)
        assertEquals(4, result.targetMappingVersion)
        assertTrue(!result.replayed)
        verify(exactly = 1) { audits.append(match { it.requestId == command.requestId && it.actor == 99L }) }
    }

    @Test
    fun `alias version conflict rechecks request id with a locking read and replays`() {
        val command = command()
        val target = ApplicationIdentitySnapshot(2, command.targetApplicationId, "target", 4)
        every { identities.findManualResult(command.requestId) } returns null
        every { identities.findByPublicId(command.targetApplicationId) } returns target
        every { identities.lockActiveAlias(command.aliasId) } returns aliasRow().copy(aliasVersion = 2)
        every { identities.lockManualResult(command.requestId) } returns completed(command)

        val result = worker.rebind(command, 99)

        assertTrue(result.replayed)
        assertEquals(2, result.aliasVersion)
        assertEquals(4, result.targetMappingVersion)
        verify(exactly = 0) { identities.lockIdentitiesInOrder(any()) }
    }

    @Test
    fun `target version conflict rechecks request id with a locking read and replays`() {
        val command = command()
        val source = ApplicationIdentitySnapshot(1, "11111111-1111-4111-8111-111111111111", "source", 6)
        val target = ApplicationIdentitySnapshot(2, command.targetApplicationId, "target", 4)
        every { identities.findManualResult(command.requestId) } returns null
        every { identities.findByPublicId(command.targetApplicationId) } returns target
        every { identities.lockActiveAlias(command.aliasId) } returns aliasRow()
        every { identities.lockManualResult(command.requestId) } returnsMany listOf(null, completed(command))
        every { identities.lockIdentitiesInOrder(listOf(1L, 2L)) } returns mapOf(1L to source, 2L to target)

        val result = worker.rebind(command, 99)

        assertTrue(result.replayed)
        assertEquals(4, result.targetMappingVersion)
        verify(exactly = 0) { identities.incrementVersions(any()) }
    }

    @Test
    fun `lock after conflict still rejects a request id bound to another command`() {
        val command = command()
        every { identities.findManualResult(command.requestId) } returns null
        every { identities.findByPublicId(command.targetApplicationId) } returns ApplicationIdentitySnapshot(
            2,
            command.targetApplicationId,
            "target",
            4
        )
        every { identities.lockActiveAlias(command.aliasId) } returns aliasRow().copy(aliasVersion = 2)
        every { identities.lockManualResult(command.requestId) } returns completed(command).copy(commandFingerprintHex = "00")

        val error = assertThrows(ServiceException::class.java) { worker.rebind(command, 99) }

        assertEquals("APPLICATION_REBIND_REQUEST_CONFLICT", error.reasonCode)
    }

    @Test
    fun `version conflict without a completed request keeps the original conflict`() {
        val command = command()
        every { identities.findManualResult(command.requestId) } returns null
        every { identities.findByPublicId(command.targetApplicationId) } returns ApplicationIdentitySnapshot(
            2,
            command.targetApplicationId,
            "target",
            4
        )
        every { identities.lockActiveAlias(command.aliasId) } returns aliasRow().copy(aliasVersion = 2)
        every { identities.lockManualResult(command.requestId) } returns null

        val error = assertThrows(ServiceException::class.java) { worker.rebind(command, 99) }

        assertEquals("APPLICATION_ALIAS_VERSION_CONFLICT", error.reasonCode)
    }

    private fun command() = ApplicationAliasRebindCommand(
        targetApplicationId = "22222222-2222-4222-8222-222222222222",
        aliasId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        expectedAliasVersion = 1,
        expectedTargetVersion = 3,
        requestId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        reason = "产品已确认属于同一应用"
    )

    private fun aliasRow() = ApplicationIdentityAliasSnapshot(
        id = 8,
        aliasPublicId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        key = ApplicationAliasKey(
            ApplicationSourceChannel.STATION_FEEDBACK,
            ApplicationAliasType.STATION_SOURCE_APP_ID,
            "station-reader"
        ),
        activeAliasKeyHex = "AB",
        applicationIdentityId = 1,
        aliasVersion = 1,
        bindingStatus = ApplicationAliasBindingStatus.ACTIVE,
        evidenceType = ApplicationAliasEvidenceType.AUTO_SAME_SOURCE,
        previousBindingId = null,
        requestId = null,
        commandFingerprintHex = null,
        targetMappingVersionSnapshot = null,
        changedBy = null,
        changeReason = null
    )

    private fun completed(command: ApplicationAliasRebindCommand) = aliasRow().copy(
        applicationIdentityId = 2,
        aliasVersion = 2,
        requestId = command.requestId,
        commandFingerprintHex = fingerprint(command),
        targetMappingVersionSnapshot = 4
    )

    private fun fingerprint(command: ApplicationAliasRebindCommand) = ApplicationIdentityCommandFingerprint.of(
        "APPLICATION_ALIAS_REBIND",
        command.targetApplicationId,
        command.aliasId,
        command.expectedAliasVersion,
        command.expectedTargetVersion,
        command.reason.trim()
    )
}
