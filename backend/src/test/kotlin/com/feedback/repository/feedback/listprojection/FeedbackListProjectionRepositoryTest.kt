package com.feedback.repository.feedback.listprojection

import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.feedback.listprojection.*
import com.feedback.model.po.feedback.listprojection.*
import com.feedback.mapper.problem.analysis.ClusterFactsMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FeedbackListProjectionRepositoryTest {
    private val generation = mockk<FeedbackListProjectionGenerationMapper>(relaxed = true)
    private val projection = mockk<FeedbackListProjectionMapper>(relaxed = true)
    private val member = mockk<FeedbackClusterMemberProjectionMapper>(relaxed = true)
    private val clusterIdentity = mockk<FeedbackClusterIdentityProjectionMapper>(relaxed = true)
    private val source = mockk<FeedbackListSourceFactsMapper>(relaxed = true)
    private val content = mockk<FeedbackListContentFactsMapper>(relaxed = true)
    private val ai = mockk<FeedbackListAiFactsMapper>(relaxed = true)
    private val workflow = mockk<FeedbackListWorkflowFactsMapper>(relaxed = true)
    private val assignment = mockk<FeedbackListAssignmentFactsMapper>(relaxed = true)
    private val owner = mockk<FeedbackListOwnerFactsMapper>(relaxed = true)
    private val handoff = mockk<FeedbackListHandoffFactsMapper>(relaxed = true)
    private val attachment = mockk<FeedbackListAttachmentFactsMapper>(relaxed = true)
    private val batch = mockk<FeedbackListImportBatchFactsMapper>(relaxed = true)
    private val row = mockk<FeedbackListImportRowFactsMapper>(relaxed = true)
    private val cluster = mockk<FeedbackListClusterLinkFactsMapper>(relaxed = true)
    private val identity = mockk<FeedbackListIdentityTypeFactsMapper>(relaxed = true)
    private val identityFingerprint = mockk<FeedbackListIdentityFingerprintFactsMapper>(relaxed = true)
    private val clusterDecision = mockk<FeedbackListClusterDecisionFactsMapper>(relaxed = true)
    private val applicationDictionary = mockk<FeedbackListApplicationDictionaryMapper>(relaxed = true)
    private val clusterFacts = mockk<ClusterFactsMapper>(relaxed = true)
    private val sdk = mockk<FeedbackListSdkFactsMapper>(relaxed = true)
    private val jira = mockk<FeedbackListJiraFactsMapper>(relaxed = true)
    private val quality = mockk<FeedbackListQualityFactsMapper>(relaxed = true)
    private val redblack = mockk<FeedbackListRedblackFactsMapper>(relaxed = true)
    private val station = mockk<FeedbackListStationFactsMapper>(relaxed = true)
    private val repository = FeedbackListProjectionRepository(
        FeedbackListProjectionGenerationRepository(generation),
        FeedbackListProjectionStoreRepository(projection, member, clusterIdentity),
        FeedbackListProjectionSourceFactsRepository(source, content, owner),
        FeedbackListProjectionAuxiliaryWorkflowRepository(ai, workflow, assignment, handoff, attachment),
        FeedbackListProjectionAuxiliaryImportRepository(batch, row),
        FeedbackListProjectionAuxiliaryClusterRepository(cluster, identity, identityFingerprint, clusterDecision, clusterFacts),
        FeedbackListProjectionAuxiliaryChannelRepository(sdk, jira, quality, redblack, station),
        FeedbackListProjectionDictionaryRepository(applicationDictionary)
    )

    @Test
    fun `empty batches execute no mapper SQL`() {
        repository.sources(emptyList())
        repository.contents(emptyList())
        repository.owners(emptyList())
        repository.auxiliary(emptyList())
        repository.replaceBatch(1, emptyList(), emptyList(), emptyList())
        verify(exactly = 0) { source.findByIds(any()) }
        verify(exactly = 0) { content.findBySourceIds(any()) }
        verify(exactly = 0) { ai.findBySourceIds(any()) }
        verify(exactly = 0) { identity.findBySourceIds(any()) }
        verify(exactly = 0) { owner.findByIds(any()) }
        verify(exactly = 0) { projection.deleteBySourceIds(any(), any()) }
    }

    @Test
    fun `all repository reads enforce the two hundred id ceiling`() {
        val tooMany = (1L..201L).toList()
        assertThrows(ClientException::class.java) { repository.sources(tooMany) }
        assertThrows(ClientException::class.java) { repository.contents(tooMany) }
        assertThrows(ClientException::class.java) { repository.owners(tooMany) }
        assertThrows(ClientException::class.java) {
            repository.auxiliary(tooMany.map { FeedbackListSourceFactsPo(id = it, sourceChannel = "SDK") })
        }
    }

    @Test
    fun `owner display names are resolved from current user facts after pagination`() {
        every { owner.findByIds(listOf(9L)) } returns listOf(FeedbackListOwnerFactsPo(9, "当前名称", "owner"))
        assertEquals("当前名称", repository.owners(listOf(9L)).getValue(9).displayName)
        verify(exactly = 1) { owner.findByIds(listOf(9L)) }
    }

    @Test
    fun `projection auxiliary loading never snapshots owner display names`() {
        repository.auxiliary(listOf(FeedbackListSourceFactsPo(id = 11, sourceChannel = "SDK")))
        verify(exactly = 0) { owner.findByIds(any()) }
    }

    @Test
    fun `writable generation lock rejects an impossible duplicate status`() {
        every { generation.findWritableForUpdate() } returns listOf(
            FeedbackListProjectionGenerationPo(id = 1, generationStatus = "ACTIVE"),
            FeedbackListProjectionGenerationPo(id = 2, generationStatus = "ACTIVE")
        )
        assertThrows(ServiceException::class.java) { repository.lockWritableGenerations() }
    }

    @Test
    fun `writable generation lock rejects mapper rows outside stable id order`() {
        every { generation.findWritableForUpdate() } returns listOf(
            FeedbackListProjectionGenerationPo(id = 2, generationStatus = "BUILDING"),
            FeedbackListProjectionGenerationPo(id = 1, generationStatus = "ACTIVE")
        )
        assertThrows(ServiceException::class.java) { repository.lockWritableGenerations() }
    }

    @Test
    fun `generation state transition requires exactly one affected row`() {
        every { generation.transitionStatus(4, "BUILDING", "FAILED", null, null, "CODE", "summary") } returns 0
        assertThrows(ServiceException::class.java) {
            repository.transition(4, "BUILDING", "FAILED", failureCode = "CODE", failureSummary = "summary")
        }
    }

    @Test
    fun `source keyset result must remain sorted unique and inside fence`() {
        every { source.findIdsAfterThroughFence(10, 20, 200) } returns listOf(12, 11)
        assertThrows(ServiceException::class.java) { repository.nextSourceIds(10, 20, 200) }
    }

    @Test
    fun `building catch up fence requires one affected row CAS`() {
        every { generation.extendBuildFence(4, 20, 20, 25, 25) } returns 0
        assertThrows(ServiceException::class.java) {
            repository.extendBuildFence(4, 20, 20, 25, 25)
        }
    }
}
