package com.feedback.service.cluster

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.common.SafeErrorDetail
import com.feedback.model.po.cluster.ClusterReclassificationRunPo
import com.feedback.repository.cluster.ClusterReclassificationRepository
import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class ClusterReclassificationStartRequest(
    val requestId: String,
    val reason: String
)

data class ClusterReclassificationRollbackRequest(
    val reason: String
)

data class ClusterReclassificationNameQualityRepairRequest(
    val reason: String
)

data class ClusterReclassificationMemberRepairRequest(
    val sourceIds: List<Long>,
    val reason: String
)

data class ClusterReclassificationView(
    val id: Long,
    val publicId: String?,
    val requestId: String,
    val runStatus: String,
    val reason: String,
    val requestedBy: Long?,
    val snapshotSafeCount: Long,
    val processedCount: Long,
    val linkedExistingCount: Long,
    val newClusterCount: Long,
    val errorDetail: String?,
    val leaseHolder: String?,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") val leaseExpiresAt: LocalDateTime?,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") val startedAt: LocalDateTime?,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") val completedAt: LocalDateTime?,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") val rolledBackAt: LocalDateTime?,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") val createdAt: LocalDateTime?,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") val updatedAt: LocalDateTime?
)

@Service
class ClusterReclassificationService(
    private val repository: ClusterReclassificationRepository,
    private val engine: ClusteringEngine,
    private val statistics: ClusterStatisticsService,
    private val transactionManager: PlatformTransactionManager,
    private val businessAudits: BusinessAuditService,
    @Value("\${app.ai.cluster-assisted-decision-enabled:false}")
    private val aiAssistedDecisionEnabled: Boolean = false
) {
    private val workerId = "cluster-reclassification-${UUID.randomUUID()}"
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate by lazy { TransactionTemplate(transactionManager) }

    @Transactional
    fun start(requestId: UUID, reason: String, userId: Long): ClusterReclassificationView {
        ensureClusterRequest(reason.trim().length in 3..500) { "CLUSTER_RECLASSIFICATION_REASON_INVALID" }
        ensureClusterRequest(!aiAssistedDecisionEnabled) { "CLUSTER_RECLASSIFICATION_AI_ASSISTED_ENABLED" }

        findByRequestId(requestId.toString())?.let { return it }
        ensureClusterRequest(!repository.hasActive()) { "CLUSTER_RECLASSIFICATION_ALREADY_RUNNING" }
        val safeCount = repository.safeCount()
        val runId = repository.create(uuidBytes(UUID.randomUUID()), requestId.toString(), reason.trim(), userId, safeCount)
        // 先固化全部旧簇生命周期，再失活链接和隔离旧簇；回滚才能恢复同一份完整事实。
        repository.snapshotAndIsolate(runId, userId)
        audit(
            userId,
            "CLUSTER_RECLASSIFICATION_STARTED",
            runId,
            mapOf("requestId" to requestId.toString(), "snapshotSafeCount" to safeCount, "reason" to reason.trim())
        )
        return findById(runId)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_RECLASSIFICATION_RESULT_MISSING")
    }

    fun latest(): ClusterReclassificationView? = repository.latest()?.let(::view)

    fun findById(runId: Long): ClusterReclassificationView? = repository.run(runId)?.let(::view)

    /** 一个有界批次；租约与全部链接/计数更新在同一事务内原子提交。 */
    fun processBatch(): Boolean {
        var claimed: Pair<Long, String>? = null
        return try {
            transactionTemplate.execute {
                claimed = claimRun()
                val run = claimed ?: return@execute false
                val sourceIds = repository.nextUnlinkedSafeSources(100)
                if (sourceIds.isEmpty()) {
                    finalizeRun(run.first)
                    return@execute true
                }
                sourceIds.forEach { sourceId ->
                    val decision = engine.cluster(sourceId, run.first)
                    ensureClusterInvariant(decision != null && decision.linkType != "AI_PENDING") {
                        "CLUSTER_RECLASSIFICATION_SOURCE_NOT_LINKED"
                    }
                }
                refreshCounts(run.first)
                repository.renew(run.first, run.second)
                true
            } ?: false
        } catch (error: Throwable) {
            if (hasTransientDataAccessCause(error)) {
                // 批次事务已经回滚。让这次运行保持可领取状态，
                // 交给下一个调度周期重试，而不是直接标记成 FAILED。
                log.warn("Cluster reclassification batch transient rollback; retrying runId={}", claimed?.first)
                return false
            }
            log.error("Cluster reclassification batch failed; runId={}", claimed?.first, error)
            claimed?.let { run ->
                runCatching {
                    transactionTemplate.execute {
                        failRun(run.first, run.second, error)
                    }
                }.onFailure { markError ->
                    log.error("Cluster reclassification failure status could not be persisted; runId={}", run.first, markError)
                }
            }
            if (claimed == null) {
                log.error("Cluster reclassification worker failed before acquiring a lease", error)
            }
            false
        }
    }

    private fun hasTransientDataAccessCause(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is TransientDataAccessException }

    fun rollback(runId: Long, reason: String, userId: Long): ClusterReclassificationView {
        ensureClusterRequest(reason.trim().length in 3..500) { "CLUSTER_RECLASSIFICATION_ROLLBACK_REASON_INVALID" }
        try {
            transactionTemplate.execute { rollbackInTransaction(runId, reason.trim(), userId) }
        } catch (error: Throwable) {
            runCatching {
                transactionTemplate.execute { markRollbackFailed(runId, userId, error) }
            }
        }
        return findById(runId)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_RECLASSIFICATION_ROLLBACK_RESULT_MISSING")
    }

    /**
     * 在把被最终名称质量门禁拒绝的运维簇隔离出来之后，显式恢复一次失败的运行。
     * 历史链接除变为失活外保持不可变；随后由普通的有界 Worker
     * 只重建那些失去 MAIN 链接的来源。
     */
    fun repairNameQualityFailure(runId: Long, reason: String, userId: Long): ClusterReclassificationView {
        ensureClusterRequest(reason.trim().length in 3..500) { "CLUSTER_RECLASSIFICATION_REPAIR_REASON_INVALID" }
        ensureClusterRequest(!aiAssistedDecisionEnabled) { "CLUSTER_RECLASSIFICATION_AI_ASSISTED_ENABLED" }
        transactionTemplate.execute {
            repairNameQualityFailureInTransaction(runId, reason.trim(), userId)
        }
        return findById(runId)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_RECLASSIFICATION_REPAIR_RESULT_MISSING")
    }

    private fun repairNameQualityFailureInTransaction(runId: Long, reason: String, userId: Long) {
        val row = repository.lockRun(runId) ?: throw ClientException(ErrorCode.NOT_FOUND, "CLUSTER_RECLASSIFICATION_NOT_FOUND")
        ensureClusterRequest(row.runStatus == "FAILED") {
            "CLUSTER_RECLASSIFICATION_NAME_QUALITY_REPAIR_NOT_ALLOWED"
        }
        ensureClusterRequest(!repository.hasActive(runId)) { "CLUSTER_RECLASSIFICATION_ALREADY_RUNNING" }
        val invalidClusterIds = repository.invalidOwnedClusters(runId)
        ensureClusterRequest(invalidClusterIds.isNotEmpty()) {
            "CLUSTER_RECLASSIFICATION_NAME_QUALITY_REPAIR_NOT_FOUND"
        }
        val unownedActiveMains = repository.countUnownedActiveMains(invalidClusterIds, runId)
        ensureClusterInvariant(unownedActiveMains == 0L, ErrorCode.CLUSTER_MAIN_RACE) {
            "CLUSTER_RECLASSIFICATION_NAME_QUALITY_REPAIR_UNOWNED_MAIN:$unownedActiveMains"
        }
        val deactivated = repository.deactivateOwnedMains(invalidClusterIds, runId)
        ensureClusterRequest(deactivated > 0) { "CLUSTER_RECLASSIFICATION_NAME_QUALITY_REPAIR_EMPTY" }
        repository.archiveWithoutActiveMain(invalidClusterIds, "重分类名称质量门禁修复后重建成员", userId, true)
        statistics.recomputeAll()
        refreshCounts(runId)
        repository.resumeFailed(runId)
        audit(userId, "CLUSTER_RECLASS_NAME_REPAIR_STARTED", runId, mapOf(
            "reason" to reason,
            "invalidClusterIds" to invalidClusterIds,
            "deactivatedMainCount" to deactivated
        ))
    }

    /** 重建显式选定的一批产出，同时保留该次运行的回滚历史。 */
    fun repairMembers(runId: Long, sourceIds: Collection<Long>, reason: String, userId: Long): ClusterReclassificationView {
        val requestedSourceIds = sourceIds.distinct()
        ensureClusterRequest(requestedSourceIds.size in 1..5000 && requestedSourceIds.all { it > 0 }) {
            "CLUSTER_RECLASSIFICATION_MEMBER_REPAIR_SOURCES_INVALID"
        }
        ensureClusterRequest(reason.trim().length in 3..500) { "CLUSTER_RECLASSIFICATION_REPAIR_REASON_INVALID" }
        ensureClusterRequest(!aiAssistedDecisionEnabled) { "CLUSTER_RECLASSIFICATION_AI_ASSISTED_ENABLED" }
        transactionTemplate.execute {
            repairMembersInTransaction(runId, requestedSourceIds, reason.trim(), userId)
        }
        return findById(runId)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_RECLASSIFICATION_MEMBER_REPAIR_RESULT_MISSING")
    }

    private fun repairMembersInTransaction(runId: Long, sourceIds: List<Long>, reason: String, userId: Long) {
        val row = repository.lockRun(runId) ?: throw ClientException(ErrorCode.NOT_FOUND, "CLUSTER_RECLASSIFICATION_NOT_FOUND")
        ensureClusterRequest(row.runStatus in setOf("FAILED", "COMPLETED")) {
            "CLUSTER_RECLASSIFICATION_MEMBER_REPAIR_NOT_ALLOWED"
        }
        ensureClusterRequest(!repository.hasActive(runId)) { "CLUSTER_RECLASSIFICATION_ALREADY_RUNNING" }
        val activeLinks = repository.ownedActiveMains(sourceIds, runId)
        val foundSourceIds = activeLinks.map { it.feedbackSourceId }.toSet()
        ensureClusterRequest(
            foundSourceIds.size == sourceIds.size && foundSourceIds.containsAll(sourceIds),
            ErrorCode.NOT_FOUND
        ) {
            "CLUSTER_RECLASSIFICATION_MEMBER_REPAIR_MAIN_MISMATCH:${sourceIds.size}:${foundSourceIds.size}"
        }
        val linkIds = activeLinks.map { it.id }
        val clusterIds = activeLinks.map { it.clusterId }.distinct()
        val deactivated = repository.deactivateLinks(linkIds)
        ensureClusterInvariant(deactivated == linkIds.size, ErrorCode.CLUSTER_MAIN_RACE) { "CLUSTER_RECLASSIFICATION_MEMBER_REPAIR_LINK_CONFLICT" }
        repository.archiveWithoutActiveMain(clusterIds, "重分类显式成员修复后无活动MAIN", userId, true)
        statistics.recompute(clusterIds)
        refreshCounts(runId)
        repository.resumeFailedOrCompleted(runId)
        audit(userId, "CLUSTER_RECLASS_MEMBER_REPAIR_STARTED", runId, mapOf(
            "reason" to reason,
            "sourceCount" to sourceIds.size,
            "sourceIdSample" to sourceIds.take(100),
            "sourceIdSampleTruncated" to (sourceIds.size > 100),
            "previousClusterIds" to clusterIds
        ))
    }

    private fun rollbackInTransaction(runId: Long, reason: String, userId: Long) {
        val row = repository.lockRun(runId) ?: throw ClientException(ErrorCode.NOT_FOUND, "CLUSTER_RECLASSIFICATION_NOT_FOUND")
        ensureClusterRequest(row.runStatus in setOf("RUNNING", "FAILED", "COMPLETED")) {
            "CLUSTER_RECLASSIFICATION_ROLLBACK_NOT_ALLOWED"
        }
        repository.beginRollback(runId, workerId)
        // 普通的调度调用可能在本轮运行创建之前就预取了某个来源，随后
        // 插入了一条 reclassification_run_id 为 NULL 的活跃 MAIN。
        // 因此在恢复快照行之前，要先失活运行期间产生的所有活跃 MAIN。
        // 运行启动时已全局失活过所有旧 MAIN，运行期间普通写入也被阻断；
        // 所以此处残留的 MAIN 要么是本轮产出，要么是预取调度的竞态产物。
        // 一并清掉它们，同时也覆盖了原本没有旧 MAIN、
        // 只处于候选状态的来源。
        repository.rollbackLinks(runId)
        repository.restoreSnapshots(runId)
        repository.archiveNewClustersWithoutActiveMain(runId, "全量重分类回滚后无活动关联")
        statistics.recomputeAll()
        repository.clearSuperseded(runId)
        repository.completeRollback(runId)
        audit(userId, "CLUSTER_RECLASSIFICATION_ROLLED_BACK", runId, mapOf("reason" to reason))
    }

    private fun markRollbackFailed(runId: Long, userId: Long, error: Throwable) {
        val detail = SafeErrorDetail.summary(error, "CLUSTER_RECLASSIFICATION_ROLLBACK")
        repository.fail(runId, null, detail)
        audit(userId, "CLUSTER_RECLASSIFICATION_FAILED", runId, mapOf("stage" to "ROLLBACK", "error" to detail))
    }

    private fun claimRun(): Pair<Long, String>? {
        val id = repository.claim(workerId) ?: return null
        return id to workerId
    }

    private fun finalizeRun(runId: Long) {
        repository.beginFinalizing(runId, workerId)
        validateCoverage(runId)
        repository.archiveEveryOperationalWithoutActiveMain(
            "全量重分类后无活动 MAIN 关联", runActor(runId), clearStatistics = true
        )
        statistics.recomputeAll()
        validateCoverage(runId)
        refreshCounts(runId)
        repository.complete(runId, workerId)
        audit(runActor(runId), "CLUSTER_RECLASSIFICATION_COMPLETED", runId, mapOf("runId" to runId))
    }

    private fun validateCoverage(runId: Long) {
        val coverage = repository.coverage(runId)
        val safe = coverage.safe
        val mains = coverage.mains
        val mainRows = coverage.mainRows
        val runMains = coverage.runMains
        val unownedSafeMains = coverage.unownedSafeMains
        ensureClusterInvariant(safe == mains && mains == mainRows && mains == runMains && unownedSafeMains == 0L) {
            "CLUSTER_RECLASSIFICATION_COVERAGE_MISMATCH:$safe:$mains:$mainRows:$runMains:$unownedSafeMains"
        }
        val duplicateActiveNames = repository.duplicateOperationalNames()
        val invalidActiveNames = repository.invalidOperationalNames()
        ensureClusterInvariant(duplicateActiveNames == 0L && invalidActiveNames == 0L) {
            "CLUSTER_RECLASSIFICATION_NAME_QUALITY_FAILED:$duplicateActiveNames:$invalidActiveNames"
        }
        repository.updateSnapshotSafeCount(runId, safe)
    }

    private fun refreshCounts(runId: Long) {
        repository.refreshCounts(runId)
    }

    private fun failRun(runId: Long, holder: String, error: Throwable) {
        val detail = SafeErrorDetail.summary(error, "CLUSTER_RECLASSIFICATION")
        repository.fail(runId, holder, detail)
        audit(runActor(runId), "CLUSTER_RECLASSIFICATION_FAILED", runId, mapOf("error" to detail))
    }

    private fun runActor(runId: Long): Long? = repository.run(runId)?.requestedBy

    private fun findByRequestId(requestId: String): ClusterReclassificationView? =
        repository.byRequestId(requestId)?.let(::view)

    private fun audit(userId: Long?, operation: String, runId: Long, detail: Map<String, Any?>) {
        businessAudits.append(BusinessAuditEvent(
            userId, operation, "SYSTEM", if (operation.endsWith("_FAILED")) "FAILED" else "SUCCESS", "CLUSTER_RECLASSIFICATION",
            runId.toString(), UUID.randomUUID().toString(), null, detail
        ))
    }

    private fun view(row: ClusterReclassificationRunPo): ClusterReclassificationView = ClusterReclassificationView(
        id = row.id ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_RECLASSIFICATION_ID_MISSING"),
        publicId = row.publicId?.let {
            val buffer = ByteBuffer.wrap(it)
            UUID(buffer.long, buffer.long).toString()
        },
        requestId = row.requestId,
        runStatus = row.runStatus,
        reason = row.reason,
        requestedBy = row.requestedBy,
        snapshotSafeCount = row.snapshotSafeCount,
        processedCount = row.processedCount,
        linkedExistingCount = row.linkedExistingCount,
        newClusterCount = row.newClusterCount,
        errorDetail = row.errorDetail,
        leaseHolder = row.leaseHolder,
        leaseExpiresAt = row.leaseExpiresAt,
        startedAt = row.startedAt,
        completedAt = row.completedAt,
        rolledBackAt = row.rolledBackAt,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt
    )

    private fun uuidBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
}

/**
 * 独立 Bean 隔离调度线程与事务服务，避免定时器绕过 Spring 事务代理。
 * 运行开关默认保持现有行为，只读评测和灾备运行可显式关闭工作线程。
 */
@Component
@ConditionalOnProperty(
    name = ["app.cluster.reclassification-worker-enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class ClusterReclassificationScheduler(
    private val service: ClusterReclassificationService,
    @Value("\${app.cluster.reclassification-worker-delay-ms:5000}")
    private val delayMs: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "cluster-reclassification-worker").apply { isDaemon = true }
    }
    private var worker: ScheduledFuture<*>? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (worker != null) return
        val delay = delayMs.coerceAtLeast(250L)
        worker = executor.scheduleWithFixedDelay(
            { service.processBatch() },
            0L,
            delay,
            TimeUnit.MILLISECONDS
        )
        log.info("Cluster reclassification worker scheduled on dedicated executor with fixed delay {} ms", delay)
    }

    @PreDestroy
    fun stop() {
        worker?.cancel(false)
        worker = null
        executor.shutdown()
    }
}
