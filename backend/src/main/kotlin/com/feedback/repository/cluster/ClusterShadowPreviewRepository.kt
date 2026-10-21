package com.feedback.repository.cluster

import com.feedback.application.port.problem.analysis.ClusterShadowPreviewDataset
import com.feedback.application.port.problem.analysis.ClusterShadowPreviewReadPort
import com.feedback.application.port.problem.analysis.ClusterShadowSafeProblemEvidence
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.cluster.ClusterShadowPreviewEvidenceMapper
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import org.springframework.stereotype.Repository
import java.security.MessageDigest

/** 只读 shadow 候选适配器；正文、来源业务标识和 canonical UUID 均不离开数据访问边界。 */
@Repository
class ClusterShadowPreviewRepository(
    private val candidates: ClusterShadowGenerationRepository,
    private val previewEvidence: ClusterShadowPreviewEvidenceMapper,
    private val identities: ApplicationIdentityRepository
) : ClusterShadowPreviewReadPort {
    override fun readVerified(generationKey: String): ClusterShadowPreviewDataset {
        val generation = candidates.findByKey(generationKey)
            ?: unavailable("CLUSTER_SHADOW_PREVIEW_GENERATION_NOT_FOUND")
        if (generation.generationStatus != "VERIFIED") {
            unavailable("CLUSTER_SHADOW_PREVIEW_GENERATION_NOT_VERIFIED")
        }
        val clusters = candidates.clusters(generation.id)
        val allMembers = candidates.members(generation.id)
        val members = allMembers.filter { it.clusterKeyHash != null }
        val evidenceRows = previewEvidence.findByGeneration(generation.id)
        val evidenceRowsBySource = evidenceRows.associateBy { it.sourceId }
        if (evidenceRowsBySource.size != evidenceRows.size) {
            unavailable("CLUSTER_SHADOW_PREVIEW_EVIDENCE_DUPLICATED")
        }
        val memberIds = members.map { it.feedbackSourceId }.toSet()
        if (evidenceRowsBySource.keys != memberIds) {
            unavailable("CLUSTER_SHADOW_PREVIEW_EVIDENCE_SET_MISMATCH")
        }
        val applications = applicationsByHash()
        val evidence = members.associate { member ->
            val row = evidenceRowsBySource.getValue(member.feedbackSourceId)
            if (row.maskingStatus != "PASS" || row.isDeleted) {
                unavailable("CLUSTER_SHADOW_PREVIEW_CONTENT_NOT_SAFE")
            }
            val application = member.applicationHash?.let { hash ->
                applications[hash] ?: unavailable("CLUSTER_SHADOW_PREVIEW_APPLICATION_NOT_DISPLAYABLE")
            }
            member.feedbackSourceId to ClusterShadowSafeProblemEvidence(
                member.feedbackSourceId, row.sourceChannel, application?.key, application?.name,
                row.title, row.description, row.functionModule, row.issueCategory
            )
        }
        return ClusterShadowPreviewDataset(
            generation,
            clusters,
            members,
            evidence,
            allMembers.filter { it.clusterKeyHash == null }.groupingBy { it.problemStatus }
                .eachCount().mapValues { it.value.toLong() }
        )
    }

    private fun applicationsByHash(): Map<String, SafeApplication> {
        val identitiesByHash = linkedMapOf<String, SafeApplication>()
        var cursor: String? = null
        repeat(MAX_IDENTITY_PAGES) {
            val page = identities.listAfter(cursor, IDENTITY_PAGE_SIZE)
            page.forEach { identity ->
                val key = identity.publicId.trim().lowercase()
                val value = SafeApplication(key, identity.displayName)
                val previous = identitiesByHash.put(shadowHash("application", key), value)
                if (previous != null && previous != value) {
                    unavailable("CLUSTER_SHADOW_PREVIEW_APPLICATION_HASH_AMBIGUOUS")
                }
            }
            if (page.size < IDENTITY_PAGE_SIZE) return identitiesByHash
            cursor = page.last().publicId
        }
        unavailable("CLUSTER_SHADOW_PREVIEW_APPLICATION_SCOPE_EXCEEDED")
    }

    private fun shadowHash(domain: String, value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("cluster-shadow-generation-v1\u0000$domain\u0000$value".toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun unavailable(reason: String): Nothing =
        throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, reason)

    private data class SafeApplication(val key: String, val name: String)

    companion object {
        private const val IDENTITY_PAGE_SIZE = 100
        private const val MAX_IDENTITY_PAGES = 10
    }
}
