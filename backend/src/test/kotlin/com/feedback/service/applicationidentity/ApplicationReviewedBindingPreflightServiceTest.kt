package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.manager.applicationidentity.*
import com.feedback.job.applicationidentity.*
import com.feedback.manager.applicationidentity.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.applicationidentity.ApplicationAliasKey
import com.feedback.model.applicationidentity.ApplicationAliasType
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.repository.applicationidentity.ApplicationIdentityReviewCandidateRepository
import com.feedback.model.applicationidentity.ApplicationIdentityLifecycleStatus
import com.feedback.service.impl.applicationidentity.ApplicationReviewedBindingPreflightServiceImpl
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import com.feedback.model.applicationidentity.ApplicationSourceChannel
import com.feedback.model.applicationidentity.ReviewedStationCandidate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationReviewedBindingPreflightServiceTest {
    private val candidates = mockk<ApplicationIdentityReviewCandidateRepository>()
    private val identities = mockk<ApplicationIdentityRepository>()
    private val service = ApplicationReviewedBindingPreflightServiceImpl(candidates, identities)

    @Test
    fun `离线准备批量解析目标展示名且不会产生逐项查询`() {
        val target = ApplicationIdentitySnapshot(
            9,
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "existing",
            7,
            ApplicationIdentityLifecycleStatus.ACTIVE
        )
        every { identities.activeByDisplayNames(listOf("existing")) } returns mapOf("existing" to listOf(target))

        val commands = service.prepare(
            listOf(
                ApplicationReviewedBindingPreparation(
                    "new-app",
                    3,
                    ApplicationReviewedBindingDecision.NEW_APPLICATION,
                    null,
                    "业务审核确认新应用"
                ),
                ApplicationReviewedBindingPreparation(
                    "existing-alias",
                    4,
                    ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING,
                    "existing",
                    "业务审核确认既有应用"
                )
            )
        )

        assertEquals(target.publicId, commands.last().targetApplicationId)
        assertEquals(target.mappingVersion, commands.last().expectedTargetVersion)
        verify(exactly = 1) { identities.activeByDisplayNames(listOf("existing")) }
        verify(exactly = 0) { identities.activeByDisplayName(any()) }
    }

    @Test
    fun `四项批准决定只读检查通过且不调用任何写边界`() {
        val commands = listOf(
            command(1, ApplicationReviewedBindingDecision.NEW_APPLICATION, 10),
            command(2, ApplicationReviewedBindingDecision.NEW_APPLICATION, 20),
            command(3, ApplicationReviewedBindingDecision.NEW_APPLICATION, 30),
            command(4, ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING, 40, targetVersion = 7)
        )
        commands.forEachIndexed { index, command ->
            every { identities.findManualResult(command.requestId) } returns null
            val key = ApplicationAliasKey(
                ApplicationSourceChannel.STATION_FEEDBACK,
                ApplicationAliasType.STATION_SOURCE_APP_ID,
                "candidate-${index + 1}"
            )
            every { candidates.resolve(command.candidateDisplayName, command.expectedSourceCount) } returns
                ReviewedStationCandidate(key, command.expectedSourceCount)
            every { identities.activeAliases(listOf(key)) } returns emptyMap()
            if (command.decision == ApplicationReviewedBindingDecision.NEW_APPLICATION) {
                every { identities.activeByDisplayName(command.candidateDisplayName) } returns emptyList()
            }
        }
        val existing = commands.last()
        every { identities.findByPublicId(existing.targetApplicationId!!) } returns ApplicationIdentitySnapshot(
            9, existing.targetApplicationId!!, "existing", existing.expectedTargetVersion!!,
            ApplicationIdentityLifecycleStatus.ACTIVE
        )

        val report = service.check(commands)

        assertTrue(report.ready)
        assertEquals(4, report.decisionCount)
        assertEquals(3, report.newApplicationCount)
        assertEquals(1, report.existingApplicationAliasCount)
        assertEquals(100L, report.expectedAffectedSourceCount)
        verify(exactly = 0) { identities.createIdentities(any()) }
        verify(exactly = 0) { identities.createAutoAliases(any()) }
        verify(exactly = 0) { identities.insertManualAlias(any()) }
        verify(exactly = 0) { identities.incrementVersions(any()) }
    }

    @Test
    fun `候选数量漂移立即失败且不会继续检查写入目标`() {
        val command = command(1, ApplicationReviewedBindingDecision.NEW_APPLICATION, 10)
        every { identities.findManualResult(command.requestId) } returns null
        every { candidates.resolve(command.candidateDisplayName, command.expectedSourceCount) } throws
            ServiceException(ErrorCode.APPLICATION_IDENTITY_CONFLICT, "APPLICATION_REVIEW_CANDIDATE_COUNT_CONFLICT")

        val error = assertThrows(ServiceException::class.java) { service.check(listOf(command)) }

        assertEquals("APPLICATION_REVIEW_CANDIDATE_COUNT_CONFLICT", error.reasonCode)
        verify(exactly = 0) { identities.activeAliases(any()) }
        verify(exactly = 0) { identities.createIdentities(any()) }
    }

    @Test
    fun `同幂等号异命令在候选读取前失败关闭`() {
        val command = command(1, ApplicationReviewedBindingDecision.NEW_APPLICATION, 10)
        every { identities.findManualResult(command.requestId) } returns mockk {
            every { commandFingerprintHex } returns "different"
        }

        val error = assertThrows(ServiceException::class.java) { service.check(listOf(command)) }

        assertEquals("APPLICATION_REVIEW_REQUEST_CONFLICT", error.reasonCode)
        verify(exactly = 0) { candidates.resolve(any(), any()) }
    }

    private fun command(
        index: Int,
        decision: ApplicationReviewedBindingDecision,
        count: Long,
        targetVersion: Long? = null
    ) = ApplicationReviewedBindingCommand(
        candidateDisplayName = "approved-candidate-$index",
        expectedSourceCount = count,
        decision = decision,
        targetApplicationId = targetVersion?.let { "aaaaaaaa-aaaa-4aaa-8aaa-${index.toString().padStart(12, '0')}" },
        expectedTargetVersion = targetVersion,
        requestId = "bbbbbbbb-bbbb-4bbb-8bbb-${index.toString().padStart(12, '0')}",
        reason = "业务审核已确认该应用身份"
    )
}
