package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.manager.applicationidentity.*
import com.feedback.job.applicationidentity.*
import com.feedback.manager.applicationidentity.*

import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.repository.applicationidentity.*
import com.feedback.model.applicationidentity.*
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

class ApplicationIdentityReviewedBindingWorkerTest {
    private val candidates = mockk<ApplicationIdentityReviewCandidateRepository>()
    private val identities = mockk<ApplicationIdentityRepository>()
    private val audits = mockk<BusinessAuditService>()
    private val worker = ApplicationIdentityReviewedBindingWorker(candidates, identities, audits)
    private val key = ApplicationAliasKey(
        ApplicationSourceChannel.STATION_FEEDBACK,
        ApplicationAliasType.STATION_SOURCE_APP_ID,
        "station-source-app"
    )

    @Test
    fun `new application persists reviewed source alias and actor`() {
        val command = command(ApplicationReviewedBindingDecision.NEW_APPLICATION)
        val target = ApplicationIdentitySnapshot(7, "22222222-2222-4222-8222-222222222222", "优学练习", 1)
        every { identities.findManualResult(command.requestId) } returns null
        every { candidates.resolve("优学练习", 60) } returns ReviewedStationCandidate(key, 60)
        every { identities.activeAliases(listOf(key)) } returns emptyMap()
        every { identities.lockManualResult(command.requestId) } returns null
        every { identities.activeByDisplayName("优学练习") } returns emptyList()
        val created = slot<List<NewApplicationIdentity>>()
        every { identities.createIdentities(capture(created)) } answers {
            val publicId = created.captured.single().publicId
            mapOf(publicId to target.copy(publicId = publicId))
        }
        every { identities.activeKeyHex(key) } returns "AB"
        val alias = slot<NewApplicationIdentityAlias>()
        every { identities.insertManualAlias(capture(alias)) } just runs
        every { audits.append(any()) } just runs

        val result = worker.create(command, 99)

        assertEquals(99, created.captured.single().createdBy)
        assertEquals("station-source-app", alias.captured.key.normalizedAlias)
        assertEquals(ApplicationAliasEvidenceType.MANUAL_REBIND, alias.captured.evidenceType)
        assertEquals(60, result.affectedSourceCount)
        verify(exactly = 1) { audits.append(match { it.operation == "APPLICATION_REVIEWED_BINDING" }) }
    }

    @Test
    fun `alias of existing increments target version before inserting alias`() {
        val command = command(
            ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING,
            targetApplicationId = "22222222-2222-4222-8222-222222222222",
            expectedTargetVersion = 3
        )
        val target = ApplicationIdentitySnapshot(7, command.targetApplicationId!!, "希沃家长圈", 3)
        every { identities.findManualResult(command.requestId) } returns null
        every { candidates.resolve("优学练习", 60) } returns ReviewedStationCandidate(key, 60)
        every { identities.activeAliases(listOf(key)) } returns emptyMap()
        every { identities.lockManualResult(command.requestId) } returns null
        every { identities.findByPublicId(target.publicId) } returns target
        every { identities.lockIdentitiesInOrder(listOf(7)) } returns mapOf(7L to target)
        every { identities.incrementVersions(listOf(target)) } returns mapOf(7L to target.copy(mappingVersion = 4))
        every { identities.activeKeyHex(key) } returns "AB"
        val alias = slot<NewApplicationIdentityAlias>()
        every { identities.insertManualAlias(capture(alias)) } just runs
        every { audits.append(any()) } just runs

        val result = worker.create(command, 99)

        assertEquals(4, result.targetMappingVersion)
        assertEquals(4, alias.captured.targetMappingVersionSnapshot)
    }

    @Test
    fun `existing active alias fails closed`() {
        val command = command(ApplicationReviewedBindingDecision.NEW_APPLICATION)
        every { identities.findManualResult(command.requestId) } returns null
        every { candidates.resolve("优学练习", 60) } returns ReviewedStationCandidate(key, 60)
        every { identities.activeAliases(listOf(key)) } returns mapOf(
            key to ActiveApplicationAlias(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                key,
                1,
                ApplicationIdentitySnapshot(1, "22222222-2222-4222-8222-222222222222", "other", 1)
            )
        )

        val error = assertThrows(ServiceException::class.java) { worker.create(command, 99) }

        assertEquals(ErrorCode.APPLICATION_IDENTITY_CONFLICT, error.errorCode)
        verify(exactly = 0) { identities.createIdentities(any()) }
    }

    @Test
    fun `matching request id replays without resolving source candidate`() {
        val command = command(ApplicationReviewedBindingDecision.NEW_APPLICATION)
        val fingerprint = reviewedBindingCommandFingerprint(command)
        every { identities.findManualResult(command.requestId) } returns ApplicationIdentityAliasSnapshot(
            id = 11,
            aliasPublicId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            key = key,
            activeAliasKeyHex = "AB",
            applicationIdentityId = 7,
            aliasVersion = 1,
            bindingStatus = ApplicationAliasBindingStatus.ACTIVE,
            evidenceType = ApplicationAliasEvidenceType.MANUAL_REBIND,
            previousBindingId = null,
            requestId = command.requestId,
            commandFingerprintHex = fingerprint,
            targetMappingVersionSnapshot = 1,
            changedBy = 99,
            changeReason = command.reason
        )
        every { identities.findById(7) } returns ApplicationIdentitySnapshot(
            7,
            "22222222-2222-4222-8222-222222222222",
            "优学练习",
            1
        )

        val result = worker.create(command, 99)

        assertTrue(result.replayed)
        verify(exactly = 0) { candidates.resolve(any(), any()) }
    }

    private fun command(
        decision: ApplicationReviewedBindingDecision,
        targetApplicationId: String? = null,
        expectedTargetVersion: Long? = null
    ) = ApplicationReviewedBindingCommand(
        candidateDisplayName = "优学练习",
        expectedSourceCount = 60,
        decision = decision,
        targetApplicationId = targetApplicationId,
        expectedTargetVersion = expectedTargetVersion,
        requestId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        reason = "产品负责人确认属于独立应用"
    )
}
