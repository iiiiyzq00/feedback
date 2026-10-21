package com.feedback.manager.cluster

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.auth.DataAccessScope
import com.feedback.model.cluster.ClusterLink
import com.feedback.repository.cluster.ClusterMutationRepository
import com.feedback.repository.cluster.ClusterProfileFactRepository
import com.feedback.service.auth.DataPermissionService
import org.springframework.stereotype.Component

/**
 * Cluster 写操作的数据范围守卫。
 *
 * 守卫位于业务协作层并组合权限服务与只读事实仓储，避免 Repository 反向依赖 Service。
 * 目标簇必须校验全部活动 MAIN 成员；空簇没有可证明范围，统一失败关闭。
 */
@Component
class ClusterMutationAccessGuard(
    private val permissions: DataPermissionService,
    private val profileFacts: ClusterProfileFactRepository,
    private val repository: ClusterMutationRepository
) {
    /** 分批校验全部来源事实均存在且落在当前主体的数据范围内。 */
    fun requireSources(sourceIds: Collection<Long>) {
        val scope = permissions.scope()
        sourceIds.distinct().sorted().chunked(ClusterProfileFactRepository.MAX_BATCH_SIZE).forEach { chunk ->
            val facts = profileFacts.accessFacts(chunk)
            if (facts.size != chunk.size) {
                throw ClientException(ErrorCode.NOT_FOUND, "FEEDBACK_NOT_FOUND")
            }
            facts.values.forEach { fact ->
                if (!scope.permits(fact.sourceChannel, fact.functionModule, fact.provinceCode, fact.cityCode)) {
                    throw ClientException(ErrorCode.AUTH_FORBIDDEN, "DATA_SCOPE_ACCESS_DENIED")
                }
            }
        }
    }

    /** 要求所有目标簇均可访问；任一簇缺失成员事实或越权时整体失败关闭。 */
    fun requireClusters(clusterIds: Collection<Long>) {
        val ids = clusterIds.distinct()
        if (!accessibleClusters(ids).containsAll(ids)) {
            throw ClientException(ErrorCode.AUTH_FORBIDDEN, "DATA_SCOPE_ACCESS_DENIED")
        }
    }

    /** 使用当前认证主体的数据范围筛选可访问簇。 */
    fun accessibleClusters(clusterIds: Collection<Long>): Set<Long> =
        accessibleClusters(clusterIds, permissions.scope())

    /**
     * 分页读取全部活动 MAIN 成员并按给定范围筛选簇。
     * 成员事实缺失视为读取不完整，禁止把部分结果误判为可访问。
     */
    fun accessibleClusters(clusterIds: Collection<Long>, scope: DataAccessScope): Set<Long> {
        val ids = clusterIds.distinct().sorted()
        if (ids.isEmpty()) {
            return emptySet()
        }
        val counts = ids.associateWith { 0 }.toMutableMap()
        val allowed = ids.toMutableSet()
        ids.chunked(ClusterMutationRepository.MAX_CLUSTER_BATCH).forEach { chunk ->
            repository.visitActiveMainPages(chunk) { page ->
                val facts = profileFacts.accessFacts(page.map(ClusterLink::feedbackSourceId))
                if (facts.size != page.size) {
                    throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "CLUSTER_ACCESS_FACT_MISSING")
                }
                page.forEach { link ->
                    counts[link.clusterId] = counts.getValue(link.clusterId) + 1
                    val fact = facts.getValue(link.feedbackSourceId)
                    if (!scope.permits(fact.sourceChannel, fact.functionModule, fact.provinceCode, fact.cityCode)) {
                        allowed -= link.clusterId
                    }
                }
            }
        }
        allowed.removeIf { counts.getValue(it) == 0 }
        return allowed
    }
}
