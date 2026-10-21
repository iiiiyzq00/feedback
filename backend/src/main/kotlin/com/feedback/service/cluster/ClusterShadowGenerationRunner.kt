package com.feedback.service.cluster

import com.feedback.common.exception.AbstractException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 默认不存在的单动作运维入口。每次启动只允许 PRECHECK、BUILD、VERIFY 或精确范围的 ABANDON_STALE；
 * 这里刻意没有 ACTIVATE 动作。BUILD 只写 shadow candidate 三张表，VERIFY 只把同一隔离
 * generation 从 BUILDING 标记为 VERIFIED，ABANDON_STALE 只关闭已明确禁止 VERIFY 的旧槽位。
 */
@Component
@ConditionalOnProperty(
    prefix = "app.cluster.shadow-generation",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class ClusterShadowGenerationRunner(
    private val service: ClusterShadowGenerationService,
    @param:Value("\${app.cluster.shadow-generation.confirmation:}") private val confirmation: String,
    @param:Value("\${app.cluster.shadow-generation.action:}") private val action: String,
    @param:Value("\${app.cluster.shadow-generation.generation-key:}") private val generationKey: String,
    @param:Value("\${app.cluster.shadow-generation.actor-id:0}") private val actorId: Long
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        try {
            ensureClusterRequest(confirmation == CONFIRMATION) {
                "CLUSTER_SHADOW_GENERATION_CONFIRMATION_REQUIRED"
            }
            val normalizedAction = action.trim().uppercase()
            if (normalizedAction == "VERIFY") {
                ensureClusterRequest(
                    generationKey.trim() != ClusterShadowGenerationService.STALE_PRE_GOVERNANCE_GENERATION
                ) { "CLUSTER_SHADOW_GENERATION_VERIFY_FORBIDDEN" }
            }
            if (normalizedAction == "ABANDON_STALE") {
                ensureClusterRequest(
                    generationKey.trim() == ClusterShadowGenerationService.STALE_PRE_GOVERNANCE_GENERATION
                ) { "CLUSTER_SHADOW_GENERATION_ABANDON_SCOPE_INVALID" }
            }
            val command = ClusterShadowGenerationCommand(generationKey, actorId)
            when (normalizedAction) {
                "PRECHECK" -> service.preflight(command)
                "BUILD" -> service.build(command)
                "VERIFY" -> service.verify(command)
                "ABANDON_STALE" -> service.abandonStale(command)
                else -> ensureClusterRequest(false) { "CLUSTER_SHADOW_GENERATION_ACTION_INVALID" }
            }
        } catch (error: AbstractException) {
            System.err.println("CLUSTER_SHADOW_GENERATION_FAILED:${error.reasonCode ?: "UNCLASSIFIED"}")
            throw error
        }
    }

    companion object {
        const val CONFIRMATION = "ISOLATED_SHADOW_CANDIDATE_ONLY"
    }
}
