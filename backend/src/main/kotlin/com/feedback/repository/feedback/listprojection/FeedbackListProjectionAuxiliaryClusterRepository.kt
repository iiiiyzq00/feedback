package com.feedback.repository.feedback.listprojection

import com.feedback.mapper.problem.analysis.ClusterFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListClusterDecisionFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListClusterLinkFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListIdentityFingerprintFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListIdentityTypeFactsMapper
import com.feedback.model.po.problem.analysis.ClusterFactsPo
import com.feedback.model.po.feedback.listprojection.*
import org.springframework.stereotype.Repository

data class FeedbackListProjectionClusterFacts(
    val links: Map<Long, FeedbackListClusterLinkFactsPo>,
    val clusters: Map<Long, ClusterFactsPo>,
    val identityTypes: Map<Long, List<FeedbackListIdentityTypeFactsPo>>,
    val identityFingerprints: Map<Long, List<FeedbackListIdentityFingerprintFactsPo>>,
    val decisions: Map<Long, FeedbackListClusterDecisionFactsPo>
)

/** 当前归簇、身份和人工决策辅助事实。 */
@Repository
class FeedbackListProjectionAuxiliaryClusterRepository(
    private val linkMapper: FeedbackListClusterLinkFactsMapper,
    private val identityTypeMapper: FeedbackListIdentityTypeFactsMapper,
    private val identityFingerprintMapper: FeedbackListIdentityFingerprintFactsMapper,
    private val decisionMapper: FeedbackListClusterDecisionFactsMapper,
    private val clusterMapper: ClusterFactsMapper
) {
    fun load(ids: List<Long>): FeedbackListProjectionClusterFacts {
        val links = linkMapper.findActiveMainBySourceIds(ids)
        val clusterIds = links.map(FeedbackListClusterLinkFactsPo::clusterId).distinct()
        return FeedbackListProjectionClusterFacts(
            links.associateBy(FeedbackListClusterLinkFactsPo::feedbackSourceId),
            if (clusterIds.isEmpty()) emptyMap() else clusterMapper.findByIds(clusterIds).associateBy(ClusterFactsPo::id),
            identityTypeMapper.findBySourceIds(ids).groupBy(FeedbackListIdentityTypeFactsPo::sourceId),
            identityFingerprintMapper.findBySourceIds(ids).groupBy(FeedbackListIdentityFingerprintFactsPo::sourceId),
            decisionMapper.findLatestBySourceIds(ids).distinctBy(FeedbackListClusterDecisionFactsPo::sourceId)
                .associateBy(FeedbackListClusterDecisionFactsPo::sourceId)
        )
    }
}
