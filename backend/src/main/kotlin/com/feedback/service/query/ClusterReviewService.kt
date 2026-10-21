package com.feedback.service.query

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.cluster.Cluster
import com.feedback.model.cluster.ClusterLink
import com.feedback.model.feedback.FeedbackAi
import com.feedback.model.cluster.stableClusterNameKey
import com.feedback.model.po.cluster.ClusterCandidateClaimPo
import com.feedback.model.po.cluster.ClusterDecisionFactPo
import com.feedback.model.po.cluster.ClusterInsertPo
import com.feedback.model.po.cluster.ClusterLinkInsertPo
import com.feedback.model.po.cluster.ClusterMainDeactivatePo
import com.feedback.repository.cluster.ClusterLedgerClaim
import com.feedback.manager.cluster.ClusterMutationAccessGuard
import com.feedback.repository.cluster.ClusterMutationRepository
import com.feedback.repository.cluster.ClusterProfileFactRepository
import com.feedback.repository.cluster.ClusterCommandLedgerRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.service.auth.DataPermissionService
import com.feedback.service.cluster.ChannelClusterContextResolver
import com.feedback.service.cluster.ClusterStatisticsService
import com.feedback.service.cluster.ProblemProfile
import com.feedback.service.cluster.ProblemProfileExtractor
import com.feedback.service.cluster.ProductScopeResolver
import com.feedback.service.cluster.clusterNameCoversProfile
import com.feedback.service.cluster.ensureClusterInvariant
import com.feedback.service.cluster.ensureClusterRequest
import com.feedback.service.cluster.sameHardProblemBoundary
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

data class ClusterReviewCommand(val expectedVersion: Long, val requestId: String, val reason: String)
data class ClusterBatchReviewItem(
    @field:Positive @field:Schema(description = "待审核反馈 source ID，须通过渠道、模块和区域范围校验") val sourceId: Long,
    @field:Pattern(regexp = "(?i)CONFIRM|REJECT|SKIP") @field:Schema(description = "审核动作", allowableValues = ["CONFIRM","REJECT","SKIP"]) val action: String,
    @field:Positive @field:Schema(description = "CONFIRM 必填的目标簇 ID；其他动作必须为空") val clusterId: Long? = null,
    @field:PositiveOrZero @field:Schema(description = "该 source 全部待审候选的期望 review_version") val expectedVersion: Long,
    @field:NotBlank @field:Schema(description = "UUID 幂等请求号；同号异命令失败关闭") val requestId: String,
    @field:Size(min=3,max=500) @field:Schema(description = "审核原因，3 至 500 字；不得填写敏感正文") val reason: String
)
data class ClusterCandidateView(
    @field:Schema(description = "候选簇 ID；仅返回操作者可访问且含活动安全成员的簇") val clusterId: Long,
    @field:Schema(description = "候选簇名称") val clusterName: String,
    @field:Schema(description = "活动 MAIN 反馈数，单位：条") val feedbackCount: Int,
    @field:Schema(description = "产品范围；未知时为空") val product: String?,
    @field:Schema(description = "功能模块；未知时为空") val module: String?,
    @field:Schema(description = "问题分类；未知时为空") val issueCategory: String?,
    @field:Schema(description = "簇生命周期状态") val clusterStatus: String,
    @field:Schema(description = "簇生命周期版本，用于状态 CAS") val lifecycleVersion: Long,
    @field:Schema(description = "规范问题现象；只含安全归纳文本") val canonicalSymptom: String?,
    @field:Schema(description = "规范问题对象") val canonicalObject: String?,
    @field:Schema(description = "规范触发动作") val canonicalTrigger: String?,
    @field:Schema(description = "非敏感错误签名") val errorSignature: String?,
    @field:Schema(description = "匹配相似度，范围 0 至 1") val similarity: Double,
    @field:Schema(description = "候选排序；未知时为空") val rank: Int?,
    @field:Schema(description = "可解释匹配证据，不包含敏感正文") val evidence: JsonNode?,
    @field:Schema(description = "是否为当前返回顺序的首选候选") val recommended: Boolean
)
data class ClusterReviewView(
    @field:Schema(description = "反馈 source ID") val id: Long,
    @field:Schema(description = "来源系统记录号；受渠道数据范围控制") val sourceRecordId: String,
    @field:Schema(description = "已脱敏标题；缺失时为空") val title: String?,
    @field:Schema(description = "已脱敏问题正文；缺失时为空") val description: String?,
    @field:Schema(description = "规范问题分类；缺失时为空") val issueCategory: String?,
    @field:Schema(description = "来源严重度，仅用于既有审核队列兼容") val severity: String?,
    @field:Schema(description = "反馈功能模块；缺失时为空") val feedbackModule: String?,
    @field:Schema(description = "权威来源渠道码") val sourceChannel: String,
    @field:Schema(description = "候选审核状态") val decision: String,
    @field:Schema(description = "首候选匹配相似度，范围 0 至 1") val similarity: Double,
    @field:Schema(description = "自动决策原因，不含敏感正文") val decisionReason: String?,
    @field:Schema(description = "归簇决策算法版本") val decisionVersion: String?,
    @field:Schema(description = "归簇配置版本") val decisionConfigVersion: String?,
    @field:Schema(description = "安全画像快照，不含原始敏感正文") val featureSnapshot: JsonNode?,
    @field:Schema(description = "审核优先级", allowableValues = ["P1","P2","P3"]) val priority: String,
    @field:Schema(description = "审核到期时间，时区由接口统一配置") val reviewDueAt: LocalDateTime,
    @field:Schema(description = "当前 review_version，提交命令时必须原样携带") val reviewVersion: Long,
    @field:Schema(description = "是否启用审核 SLA 展示") val slaEnabled: Boolean,
    @field:Schema(description = "距到期秒数；SLA 未启用时为空，过期可为负数") val slaRemainingSeconds: Long?,
    @field:Schema(description = "是否已超过审核到期时间") val overdue: Boolean,
    @field:Schema(description = "可访问候选数量") val candidateCount: Int,
    @field:Schema(description = "可访问候选簇，按候选排名稳定排序") val candidates: List<ClusterCandidateView>
)
data class ClusterReviewPage(
    @field:Schema(description = "当前页人工归簇审核记录") val records: List<ClusterReviewView>,
    @field:Schema(description = "按当前数据范围过滤后的记录总数") val total: Long,
    @field:Schema(description = "页码，从 1 开始") val page: Int,
    @field:Schema(description = "每页条数，1 至 100") val size: Int
)

@Service
class ClusterReviewService(
    private val repository: ClusterMutationRepository,
    private val profileFacts: ClusterProfileFactRepository,
    private val commandLedger: ClusterCommandLedgerRepository,
    private val accessGuard: ClusterMutationAccessGuard,
    private val permissions: DataPermissionService,
    private val statistics: ClusterStatisticsService,
    private val businessAudits: BusinessAuditService,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker,
    private val productScopes: ProductScopeResolver,
    private val profileExtractor: ProblemProfileExtractor,
    private val channelContexts: ChannelClusterContextResolver,
    private val json: ObjectMapper,
    @Value("\${app.review-governance.sla-enabled:false}") private val slaEnabled: Boolean = false
) {
    /**
     * review queue 以 link 单表稳定 keyset 分页，再批量读取 source/content/cluster/member facts。
     * 数据范围过滤在应用层完成，但不会把全量队列载入内存，且公开 JSON 字段保持兼容。
     */
    fun reviews(priority: String?, page: Int, size: Int): ClusterReviewPage {
        ensureClusterRequest(page >= 1 && size in 1..100) { "PAGE_INVALID" }
        val normalized = priority?.takeIf(String::isNotBlank)?.uppercase()
        ensureClusterRequest(normalized == null || normalized in PRIORITIES) { "CLUSTER_PRIORITY_INVALID" }
        val offset = (page.toLong() - 1L) * size.toLong()
        val records = mutableListOf<ClusterReviewView>()
        var total = 0L
        var afterPriority: String? = null
        var afterDueAt: LocalDateTime? = null
        var afterSourceId = 0L
        val scope = permissions.scope()
        val now = LocalDateTime.now()
        while (true) {
            val queue = repository.pendingReviewSourcesAfter(
                afterPriority, afterDueAt, afterSourceId, normalized, REVIEW_SCAN_PAGE
            )
            if (queue.isEmpty()) break
            val sourceIds = queue.map { it.feedbackSourceId }
            val facts = profileFacts.load(sourceIds)
            val candidates = repository.pendingCandidates(sourceIds).groupBy(ClusterLink::feedbackSourceId)
            val clusterIds = candidates.values.flatten().map(ClusterLink::clusterId).distinct()
            val clusters = clusterIds.chunked(ClusterMutationRepository.MAX_CLUSTER_BATCH)
                .flatMap(repository::clusters).associateBy(Cluster::id)
            val accessibleTargets = accessGuard.accessibleClusters(clusterIds, scope)
            queue.forEach { row ->
                val source = facts.sources[row.feedbackSourceId]
                val content = facts.contents[row.feedbackSourceId]
                val sourceCandidates = candidates[row.feedbackSourceId].orEmpty()
                    .filter { it.clusterId in accessibleTargets && clusters.containsKey(it.clusterId) }
                if (source == null || content == null || content.isDeleted || content.maskingStatus != "PASS" ||
                    !scope.permits(source.sourceChannel, content.functionModule, source.provinceCode, source.cityCode) ||
                    sourceCandidates.isEmpty()) return@forEach
                if (total >= offset && records.size < size) {
                    records += toReviewView(source.id, source.sourceRecordId, source.sourceChannel, content,
                        sourceCandidates, clusters, row.priority, row.dueAt, now)
                }
                total++
            }
            val last = queue.last()
            afterPriority = last.priority
            afterDueAt = last.dueAt
            afterSourceId = last.feedbackSourceId
            if (queue.size < REVIEW_SCAN_PAGE) break
        }
        return ClusterReviewPage(records, total, page, size)
    }

    @Transactional
    fun confirm(sourceId: Long, clusterId: Long, command: ClusterReviewCommand, reviewerId: Long) {
        reviewBatch(listOf(ClusterBatchReviewItem(sourceId, "CONFIRM", clusterId,
            command.expectedVersion, command.requestId, command.reason)), reviewerId)
    }

    /** 201 条及所有结构错误必须在第一次 Mapper 调用前拒绝。 */
    @Transactional
    fun reviewBatch(items: List<ClusterBatchReviewItem>, reviewerId: Long): Int {
        validateBatchBeforePersistence(items)
        val ordered = items.sortedBy(ClusterBatchReviewItem::sourceId)
        val claims = linkedMapOf<Long, Pair<ClusterBatchReviewItem, ClusterLedgerClaim>>()
        ordered.forEach { item ->
            val operation = operationFor(item.action)
            val command = item.command()
            validate(command)
            val claim = claim(operation, item.sourceId, command, listOf(item.clusterId, item.action.uppercase()))
            claims[item.sourceId] = item to claim
        }
        val pending = claims.values.filterNot { it.second.completed }.map { it.first }
        if (pending.isEmpty()) return items.size

        val sourceIds = pending.map(ClusterBatchReviewItem::sourceId)
        accessGuard.requireSources(sourceIds)
        val confirmSourceIds = pending.filter { it.action.equals("CONFIRM", ignoreCase = true) }
            .map(ClusterBatchReviewItem::sourceId)
        val currentBefore = repository.activeMainBySources(confirmSourceIds)
        val targetIds = pending.mapNotNull(ClusterBatchReviewItem::clusterId).toSet()
        val clusterIdsToLock = (targetIds + currentBefore.values.map(ClusterLink::clusterId)).sorted()
        val lockedClusters = clusterIdsToLock.chunked(ClusterMutationRepository.MAX_CLUSTER_BATCH)
            .flatMap(repository::lockClusters).associateBy(Cluster::id)
        accessGuard.requireClusters(clusterIdsToLock)
        targetIds.forEach { id ->
            val target = lockedClusters[id] ?: throw ClientException(ErrorCode.NOT_FOUND, "CLUSTER_NOT_FOUND")
            ensureClusterRequest(target.clusterStatus in ACTIVE_TARGET_STATUSES) { "CLUSTER_TARGET_NOT_ACTIVE_USE_REOPEN" }
        }
        val lockedLinks = repository.linksBySources(sourceIds, lock = true)
        val candidatesBySource = lockedLinks.filter {
            it.isActive && it.relationRole == "CANDIDATE" && it.reviewStatus == "PENDING"
        }.groupBy(ClusterLink::feedbackSourceId)
        val mainsBySource = lockedLinks.filter { it.isActive && it.relationRole == "MAIN" }
            .associateBy(ClusterLink::feedbackSourceId)
        ensureClusterInvariant(mainsBySource.size == lockedLinks.count { it.isActive && it.relationRole == "MAIN" }) {
            "CLUSTER_ACTIVE_MAIN_DUPLICATED"
        }
        confirmSourceIds.forEach { sourceId ->
            val before = currentBefore[sourceId]
            val after = mainsBySource[sourceId]
            ensureClusterInvariant(
                before?.id == after?.id && before?.clusterId == after?.clusterId,
                ErrorCode.CLUSTER_MAIN_RACE
            ) {
                "CLUSTER_MAIN_CHANGED_DURING_LOCK"
            }
        }

        val candidateClaims = mutableListOf<ClusterCandidateClaimPo>()
        val mainDeactivations = mutableListOf<ClusterMainDeactivatePo>()
        val mainInserts = mutableListOf<ClusterLinkInsertPo>()
        val dirtySourceIds = linkedSetOf<Long>()
        val statisticsClusterIds = linkedSetOf<Long>()
        val decisionInputs = mutableListOf<ReviewDecisionInput>()
        pending.forEach { item ->
            val command = item.command()
            val action = item.action.uppercase()
            val candidates = candidatesBySource[item.sourceId].orEmpty()
            validatePendingCandidates(candidates, command.expectedVersion)
            val targetId = item.clusterId
            if (action == "CONFIRM") ensureClusterRequest(candidates.any { it.clusterId == targetId }) {
                "CLUSTER_NOT_CANDIDATE"
            }
            candidateClaims += ClusterCandidateClaimPo(
                sourceId = item.sourceId,
                expectedVersion = command.expectedVersion,
                selectedClusterId = if (action == "CONFIRM") targetId else null,
                status = if (action == "CONFIRM") "CONFIRMED" else if (action == "REJECT") "REJECTED" else "PENDING",
                reviewerId = reviewerId,
                reason = command.reason.trim(),
                requestId = command.requestId,
                deactivate = action != "SKIP"
            )
            val current = mainsBySource[item.sourceId]
            var mainChanged = false
            if (action == "CONFIRM" && current?.clusterId != targetId) {
                if (current != null) {
                    mainDeactivations += ClusterMainDeactivatePo(
                        current.id, current.reviewVersion, reviewerId, command.reason.trim(), null,
                        "人工重新分配，旧主关联停用"
                    )
                    statisticsClusterIds += current.clusterId
                }
                val selected = candidates.firstOrNull { it.clusterId == targetId }
                    ?: throw ServiceException(ErrorCode.CLUSTER_REVIEW_CONFLICT, "CLUSTER_REVIEW_TARGET_NOT_CANDIDATE")
                mainInserts += ClusterLinkInsertPo(
                    clusterId = targetId
                        ?: throw ClientException(ErrorCode.BAD_REQUEST, "CLUSTER_REVIEW_BATCH_CLUSTER_REQUIRED"),
                    feedbackSourceId = item.sourceId,
                    relationRole = "MAIN", linkType = if (current == null) "MANUAL_CONFIRMED" else "MANUAL_REASSIGNED",
                    matchSimilarity = selected.matchSimilarity, matchEvidence = selected.matchEvidence,
                    decisionVersion = selected.decisionVersion,
                    decisionConfigVersion = selected.decisionConfigVersion,
                    featureSnapshot = selected.featureSnapshot,
                    problemFingerprint = selected.problemFingerprint,
                    reviewStatus = "CONFIRMED", reviewedBy = reviewerId,
                    reviewReason = command.reason.trim(), decisionReason = "人工确认主关联"
                )
                statisticsClusterIds += targetId
                dirtySourceIds += item.sourceId
                mainChanged = true
            }
            decisionInputs += ReviewDecisionInput(item, candidates, current, targetId, mainChanged)
        }

        repository.claimCandidates(candidateClaims, decisionInputs.sumOf { it.candidates.size })
        repository.deactivateMainLinks(mainDeactivations)
        repository.insertLinks(mainInserts)
        statisticsClusterIds.sorted().chunked(ClusterMutationRepository.MAX_CLUSTER_BATCH).forEach(statistics::recompute)
        decisionInputs.forEach { input ->
            val ledger = claims[input.item.sourceId]?.second
                ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_REVIEW_LEDGER_MISSING")
            finishReviewDecision(input, reviewerId, ledger)
        }
        if (dirtySourceIds.isNotEmpty()) dirtyMarker.mark(FeedbackListProjectionDirtyCommand(
            dirtySourceIds.sorted(), FeedbackListProjectionDirtyReason.CLUSTER
        ))
        return items.size
    }

    @Transactional
    fun create(sourceId: Long, name: String, command: ClusterReviewCommand, reviewerId: Long): Long {
        validate(command)
        ensureClusterRequest(sourceId > 0) { "CLUSTER_SOURCE_ID_INVALID" }
        ensureClusterRequest(name.isNotBlank() && name.trim().length <= 200) { "CLUSTER_NAME_INVALID" }
        val normalizedName = name.trim()
        val ledgerClaim = claim("CLUSTER_REVIEW_CREATE", sourceId, command, listOf(normalizedName))
        if (ledgerClaim.completed) return replayLong(ledgerClaim, "clusterId")
        accessGuard.requireSources(listOf(sourceId))
        val facts = profileFacts.load(listOf(sourceId))
        val source = facts.sources[sourceId] ?: throw ClientException(ErrorCode.NOT_FOUND, "FEEDBACK_NOT_FOUND")
        val content = facts.contents[sourceId] ?: throw ClientException(ErrorCode.NOT_FOUND, "FEEDBACK_NOT_FOUND")
        val ai = facts.ai[sourceId] ?: FeedbackAi(sourceId = sourceId, reviewStatus = "NOT_REQUIRED")
        val profile = profileExtractor.extract(source, content, ai, productScopes.resolve(source),
            facts.contexts[sourceId]?.asMap() ?: channelContexts.resolve(sourceId))
        ensureClusterRequest(profile.informationQuality == "ACTIONABLE" && clusterNameCoversProfile(normalizedName, profile)) {
            "CLUSTER_NAME_DOES_NOT_COVER_PROFILE"
        }
        val nameKey = stableClusterNameKey(normalizedName)
        val currentBefore = repository.activeMainBySources(listOf(sourceId))[sourceId]
        val existingSnapshot = repository.byNameKeys(listOf(nameKey)).firstOrNull()
        val preexistingIds = listOfNotNull(currentBefore?.clusterId, existingSnapshot?.id).distinct().sorted()
        val lockedPreexisting = if (preexistingIds.isEmpty()) emptyMap() else
            repository.lockClusters(preexistingIds).associateBy(Cluster::id)
        val existing = existingSnapshot?.let { snapshot ->
            val locked = lockedPreexisting[snapshot.id] ?: throw ClientException(ErrorCode.NOT_FOUND, "CLUSTER_NOT_FOUND")
            ensureClusterInvariant(locked.clusterNameKey == nameKey, ErrorCode.CLUSTER_MAIN_RACE) {
                "CLUSTER_NAME_CHANGED_DURING_LOCK"
            }
            locked
        }
        existing?.let { validateCreateTarget(it, profile) }
        val inserted = if (existing == null) {
            val concurrent = repository.byNameKeys(listOf(nameKey)).firstOrNull()
            if (concurrent != null) {
                if (currentBefore != null && concurrent.id < currentBefore.clusterId) {
                    throw ServiceException(ErrorCode.CLUSTER_REVIEW_CONFLICT, "CLUSTER_LOCK_ORDER_RETRY")
                }
                val locked = repository.lockClusters(listOf(concurrent.id)).singleOrNull()
                    ?: throw ServiceException(ErrorCode.CLUSTER_MAIN_RACE, "CLUSTER_CONCURRENT_TARGET_LOCK_MISSING")
                ensureClusterInvariant(locked.clusterNameKey == nameKey, ErrorCode.CLUSTER_MAIN_RACE) {
                    "CLUSTER_NAME_CHANGED_DURING_LOCK"
                }
                validateCreateTarget(locked, profile)
                locked to false
            } else repository.insertOrReuse(ClusterInsertPo(
                publicId = uuidBytes(), clusterName = normalizedName, clusterNameKey = nameKey,
                productScope = profile.productScope, functionModule = content.functionModule,
                issueCategory = content.issueCategory, canonicalSymptom = normalizedName,
                canonicalObject = profile.targetObject, canonicalTrigger = profile.trigger,
                errorSignature = profile.errorSignature
            )).also { (target, created) ->
                if (!created && currentBefore != null && target.id < currentBefore.clusterId) {
                    throw ServiceException(ErrorCode.CLUSTER_REVIEW_CONFLICT, "CLUSTER_LOCK_ORDER_RETRY")
                }
                validateCreateTarget(target, profile)
            }
        } else existing to false
        val cluster = inserted.first
        val changed = completeCreatedMain(sourceId, cluster, currentBefore, command, reviewerId,
            ledgerClaim, profile.problemFingerprint, targetCreated = inserted.second)
        if (changed) repository.updateProblemFingerprint(sourceId, cluster.id, profile.problemFingerprint)
        return cluster.id
    }

    @Transactional
    fun reject(sourceId: Long, command: ClusterReviewCommand, reviewerId: Long) {
        reviewBatch(listOf(ClusterBatchReviewItem(sourceId, "REJECT", null,
            command.expectedVersion, command.requestId, command.reason)), reviewerId)
    }

    @Transactional
    fun skip(sourceId: Long, command: ClusterReviewCommand, reviewerId: Long) {
        reviewBatch(listOf(ClusterBatchReviewItem(sourceId, "SKIP", null,
            command.expectedVersion, command.requestId, command.reason)), reviewerId)
    }

    @Transactional
    fun unlink(sourceId: Long, command: ClusterReviewCommand, reviewerId: Long) {
        validate(command)
        ensureClusterRequest(sourceId > 0) { "CLUSTER_SOURCE_ID_INVALID" }
        val ledgerClaim = claim("CLUSTER_MAIN_UNLINK", sourceId, command, emptyList())
        if (ledgerClaim.completed) return
        accessGuard.requireSources(listOf(sourceId))
        val currentBefore = repository.activeMainBySources(listOf(sourceId))[sourceId]
            ?: throw ClientException(ErrorCode.NOT_FOUND, "CLUSTER_LINK_NOT_FOUND")
        repository.lockClusters(listOf(currentBefore.clusterId))
        accessGuard.requireClusters(listOf(currentBefore.clusterId))
        val main = repository.activeMainBySources(listOf(sourceId), lock = true)[sourceId]
            ?: throw ClientException(ErrorCode.NOT_FOUND, "CLUSTER_LINK_NOT_FOUND")
        ensureClusterInvariant(main.clusterId == currentBefore.clusterId, ErrorCode.CLUSTER_MAIN_RACE) {
            "CLUSTER_MAIN_CHANGED_DURING_LOCK"
        }
        repository.deactivateMainLinks(listOf(ClusterMainDeactivatePo(
            main.id, command.expectedVersion, reviewerId, command.reason.trim(), command.requestId,
            "人工解除主关联"
        )))
        statistics.recomputeOne(main.clusterId)
        recordDecision(sourceId, null, "UNLINK", true, emptyList(), command, reviewerId)
        audit(reviewerId, "CLUSTER_MAIN_UNLINK", sourceId, main.clusterId, null, 1, command)
        dirtyMarker.mark(FeedbackListProjectionDirtyCommand(
            listOf(sourceId), FeedbackListProjectionDirtyReason.CLUSTER
        ))
        commandLedger.complete(ledgerClaim.ledgerId, command.requestId, mapOf(
            "unlinked" to true, "beforeClusterId" to main.clusterId, "afterClusterId" to null,
            "affectedCount" to 1
        ))
    }

    private fun completeCreatedMain(
        sourceId: Long,
        target: Cluster,
        currentBefore: ClusterLink?,
        command: ClusterReviewCommand,
        reviewerId: Long,
        ledgerClaim: ClusterLedgerClaim,
        fingerprint: String,
        targetCreated: Boolean
    ): Boolean {
        val clusterId = target.id
        val ids = listOfNotNull(currentBefore?.clusterId, clusterId).distinct().sorted()
        ensureClusterRequest(target.clusterStatus in ACTIVE_TARGET_STATUSES) { "CLUSTER_TARGET_NOT_ACTIVE_USE_REOPEN" }
        accessGuard.requireClusters(ids.filter { it != clusterId || !targetCreated })
        val rows = repository.linksBySources(listOf(sourceId), lock = true)
        val candidates = rows.filter { it.isActive && it.relationRole == "CANDIDATE" && it.reviewStatus == "PENDING" }
        validatePendingCandidates(candidates, command.expectedVersion)
        val current = rows.singleOrNull { it.isActive && it.relationRole == "MAIN" }
        ensureClusterInvariant(
            current?.id == currentBefore?.id && current?.clusterId == currentBefore?.clusterId,
            ErrorCode.CLUSTER_MAIN_RACE
        ) {
            "CLUSTER_MAIN_CHANGED_DURING_LOCK"
        }
        repository.claimCandidates(listOf(ClusterCandidateClaimPo(
            sourceId, command.expectedVersion, clusterId, "CONFIRMED", reviewerId,
            command.reason.trim(), command.requestId, true
        )), candidates.size)
        var changed = false
        if (current?.clusterId != clusterId) {
            if (current != null) repository.deactivateMainLinks(listOf(ClusterMainDeactivatePo(
                current.id, current.reviewVersion, reviewerId, command.reason.trim(), null,
                "人工重新分配，旧主关联停用"
            )))
            repository.insertLinks(listOf(ClusterLinkInsertPo(
                clusterId = clusterId, feedbackSourceId = sourceId, relationRole = "MAIN",
                linkType = if (current == null) "MANUAL_CONFIRMED" else "MANUAL_REASSIGNED",
                problemFingerprint = fingerprint, reviewStatus = "CONFIRMED", reviewedBy = reviewerId,
                reviewReason = command.reason.trim(), decisionReason = "人工创建并确认主关联"
            )))
            listOfNotNull(current?.clusterId, clusterId).distinct().sorted()
                .chunked(ClusterMutationRepository.MAX_CLUSTER_BATCH).forEach(statistics::recompute)
            changed = true
        }
        recordDecision(sourceId, clusterId, "CREATE_CLUSTER", true, candidates, command, reviewerId)
        audit(reviewerId, if (current == null) "CLUSTER_REVIEW_CREATE" else "CLUSTER_REVIEW_REASSIGN",
            sourceId, current?.clusterId, clusterId, if (changed) 1 else 0, command)
        if (changed) dirtyMarker.mark(FeedbackListProjectionDirtyCommand(
            listOf(sourceId), FeedbackListProjectionDirtyReason.CLUSTER
        ))
        commandLedger.complete(ledgerClaim.ledgerId, command.requestId, mapOf(
            "clusterId" to clusterId, "beforeClusterId" to current?.clusterId,
            "afterClusterId" to clusterId, "affectedCount" to if (changed) 1 else 0
        ))
        return changed
    }

    private fun validateCreateTarget(cluster: Cluster, profile: ProblemProfile) {
        ensureClusterRequest(cluster.clusterStatus != "RESOLVED") { "CLUSTER_NAME_RESOLVED_USE_REOPEN" }
        ensureClusterRequest(sameHardProblemBoundary(cluster, profile)) { "CLUSTER_NAME_PROFILE_CONFLICT_RENAME_REQUIRED" }
    }

    private data class ReviewDecisionInput(
        val item: ClusterBatchReviewItem,
        val candidates: List<ClusterLink>,
        val current: ClusterLink?,
        val targetId: Long?,
        val mainChanged: Boolean
    )

    private fun finishReviewDecision(input: ReviewDecisionInput, reviewerId: Long, ledgerClaim: ClusterLedgerClaim) {
        val command = input.item.command()
        val action = input.item.action.uppercase()
        val selected = if (action == "CONFIRM") input.targetId else null
        val type = when (action) {
            "CONFIRM" -> if (input.current != null) "REASSIGN" else "CONFIRM"
            "REJECT" -> "REJECT_ALL"
            else -> "SKIP"
        }
        if (action != "SKIP") recordDecision(input.item.sourceId, selected, type,
            action == "REJECT" || input.mainChanged, input.candidates, command, reviewerId)
        val operation = when (action) {
            "CONFIRM" -> if (input.current != null) "CLUSTER_REVIEW_REASSIGN" else "CLUSTER_REVIEW_CONFIRM"
            "REJECT" -> "CLUSTER_REVIEW_REJECT"
            else -> "CLUSTER_REVIEW_SKIP"
        }
        audit(reviewerId, operation, input.item.sourceId, input.current?.clusterId, selected,
            if (input.mainChanged) 1 else 0, command)
        commandLedger.complete(ledgerClaim.ledgerId, command.requestId, mapOf(
            "reviewed" to true, "action" to action,
            "beforeClusterId" to input.current?.clusterId, "afterClusterId" to selected,
            "affectedCount" to if (input.mainChanged) 1 else 0
        ))
    }

    private fun recordDecision(
        sourceId: Long, selectedClusterId: Long?, type: String, overturned: Boolean,
        candidates: List<ClusterLink>, command: ClusterReviewCommand, reviewerId: Long
    ) {
        val top = candidates.minByOrNull { it.candidateRank ?: Int.MAX_VALUE }?.clusterId
        val snapshot = json.writeValueAsString(mapOf(
            "requestId" to command.requestId, "topCandidateClusterId" to top,
            "selectedClusterId" to selectedClusterId, "isOverturn" to overturned
        ))
        commandLedger.recordDecision(ClusterDecisionFactPo(
            factKey = commandLedger.fingerprint(command.requestId), decisionType = type,
            overturned = overturned, requestId = command.requestId, candidateCount = candidates.size,
            sourceId = sourceId, selectedClusterId = selectedClusterId, reason = command.reason.trim(),
            reviewerId = reviewerId, snapshot = snapshot
        ))
    }

    private fun audit(
        reviewerId: Long, operation: String, sourceId: Long, beforeClusterId: Long?,
        afterClusterId: Long?, affectedCount: Int, command: ClusterReviewCommand
    ) {
        businessAudits.append(BusinessAuditEvent(
            reviewerId, operation, "BUSINESS", "SUCCESS", "CLUSTER_REVIEW", sourceId.toString(),
            command.requestId, null, mapOf(
                "sourceId" to sourceId, "actor" to reviewerId, "requestId" to command.requestId,
                "expectedVersion" to command.expectedVersion, "reason" to command.reason.trim(),
                "beforeClusterId" to beforeClusterId, "afterClusterId" to afterClusterId,
                "affectedCount" to affectedCount
            )
        ))
    }

    private fun claim(
        operation: String, targetId: Long, command: ClusterReviewCommand, extra: List<Any?>
    ): ClusterLedgerClaim {
        val fingerprint = commandLedger.fingerprint(
            operation, targetId, command.expectedVersion, command.reason.trim(), extra
        )
        val claim = commandLedger.claim(command.requestId, operation, fingerprint, targetId)
        if (claim.operation != operation || claim.commandFingerprint != fingerprint) {
            throw ServiceException(ErrorCode.CLUSTER_REVIEW_CONFLICT, "CLUSTER_REQUEST_ID_COMMAND_CONFLICT")
        }
        return claim
    }

    private fun validateBatchBeforePersistence(items: List<ClusterBatchReviewItem>) {
        ensureClusterRequest(items.isNotEmpty() && items.size <= MAX_REVIEW_BATCH_SIZE) { "CLUSTER_REVIEW_BATCH_SIZE_INVALID" }
        ensureClusterRequest(items.map(ClusterBatchReviewItem::sourceId).distinct().size == items.size) {
            "CLUSTER_REVIEW_BATCH_SOURCE_DUPLICATED"
        }
        ensureClusterRequest(items.map(ClusterBatchReviewItem::requestId).distinct().size == items.size) {
            "CLUSTER_REVIEW_BATCH_REQUEST_DUPLICATED"
        }
        items.forEach { item ->
            ensureClusterRequest(item.sourceId > 0) { "CLUSTER_SOURCE_ID_INVALID" }
            val action = item.action.uppercase()
            ensureClusterRequest(action in REVIEW_ACTIONS) { "CLUSTER_REVIEW_BATCH_ACTION_INVALID" }
            if (action == "CONFIRM") ensureClusterRequest(item.clusterId != null && item.clusterId > 0) {
                "CLUSTER_REVIEW_BATCH_CLUSTER_REQUIRED"
            } else ensureClusterRequest(item.clusterId == null) { "CLUSTER_REVIEW_BATCH_CLUSTER_NOT_ALLOWED" }
            ensureClusterRequest(item.expectedVersion >= 0) { "CLUSTER_REVIEW_VERSION_INVALID" }
            ensureClusterRequest(item.reason.trim().length in 3..500) { "CLUSTER_REVIEW_REASON_INVALID" }
            runCatching { UUID.fromString(item.requestId) }
                .getOrElse { throw ClientException(ErrorCode.BAD_REQUEST, "CLUSTER_REVIEW_REQUEST_ID_INVALID", cause = it) }
        }
    }

    private fun validate(command: ClusterReviewCommand) {
        ensureClusterRequest(command.expectedVersion >= 0) { "CLUSTER_REVIEW_VERSION_INVALID" }
        runCatching { UUID.fromString(command.requestId) }
            .getOrElse { throw ClientException(ErrorCode.BAD_REQUEST, "CLUSTER_REVIEW_REQUEST_ID_INVALID", cause = it) }
        ensureClusterRequest(command.reason.trim().length in 3..500) { "CLUSTER_REVIEW_REASON_INVALID" }
    }

    private fun validatePendingCandidates(candidates: List<ClusterLink>, expectedVersion: Long) {
        if (candidates.isEmpty()) throw ServiceException(ErrorCode.CLUSTER_REVIEW_CONFLICT, "CLUSTER_REVIEW_ALREADY_HANDLED")
        if (candidates.any { it.reviewVersion != expectedVersion || it.reviewStatus != "PENDING" }) {
            throw ServiceException(ErrorCode.CLUSTER_REVIEW_CONFLICT, "CLUSTER_REVIEW_VERSION_CONFLICT")
        }
    }

    private fun toReviewView(
        sourceId: Long, sourceRecordId: String, sourceChannel: String,
        content: com.feedback.model.feedback.FeedbackContent, candidates: List<ClusterLink>,
        clusters: Map<Long, Cluster>, priority: String, dueAt: LocalDateTime, now: LocalDateTime
    ): ClusterReviewView {
        val first = candidates.firstOrNull()
            ?: throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "CLUSTER_REVIEW_CANDIDATE_MISSING")
        return ClusterReviewView(
            id = sourceId, sourceRecordId = sourceRecordId, title = content.title,
            description = content.description, issueCategory = content.issueCategory,
            severity = content.severityLevel, feedbackModule = content.functionModule,
            sourceChannel = sourceChannel, decision = first.reviewStatus,
            similarity = first.matchSimilarity?.toDouble() ?: 0.0,
            decisionReason = first.decisionReason, decisionVersion = first.decisionVersion,
            decisionConfigVersion = first.decisionConfigVersion,
            featureSnapshot = first.featureSnapshot?.let(json::readTree), priority = priority,
            reviewDueAt = dueAt, reviewVersion = first.reviewVersion, slaEnabled = slaEnabled,
            slaRemainingSeconds = if (slaEnabled) Duration.between(now, dueAt).seconds else null,
            overdue = slaEnabled && now.isAfter(dueAt), candidateCount = candidates.size,
            candidates = candidates.mapIndexed { index, link ->
                val cluster = clusters[link.clusterId]
                    ?: throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "CLUSTER_REVIEW_TARGET_MISSING")
                ClusterCandidateView(
                    clusterId = cluster.id, clusterName = cluster.clusterName,
                    feedbackCount = cluster.totalRecordCount, product = cluster.productScope,
                    module = cluster.functionModule, issueCategory = cluster.issueCategory,
                    clusterStatus = cluster.clusterStatus, lifecycleVersion = cluster.lifecycleVersion,
                    canonicalSymptom = cluster.canonicalSymptom, canonicalObject = cluster.canonicalObject,
                    canonicalTrigger = cluster.canonicalTrigger, errorSignature = cluster.errorSignature,
                    similarity = link.matchSimilarity?.toDouble() ?: 0.0, rank = link.candidateRank,
                    evidence = link.matchEvidence?.let(json::readTree), recommended = index == 0
                )
            }
        )
    }

    private fun replayLong(claim: ClusterLedgerClaim, field: String): Long =
        claim.result?.get(field)?.asLong()
            ?: throw ServiceException(ErrorCode.CLUSTER_REVIEW_CONFLICT, "CLUSTER_REPLAY_RESULT_MISSING")

    private fun operationFor(action: String): String = when (action.uppercase()) {
        "CONFIRM" -> "CLUSTER_REVIEW_CONFIRM"
        "REJECT" -> "CLUSTER_REVIEW_REJECT"
        "SKIP" -> "CLUSTER_REVIEW_SKIP"
        else -> throw ClientException(ErrorCode.BAD_REQUEST, "CLUSTER_REVIEW_BATCH_ACTION_INVALID")
    }

    private fun ClusterBatchReviewItem.command() = ClusterReviewCommand(expectedVersion, requestId, reason)
    private fun uuidBytes(): ByteArray = UUID.randomUUID().let {
        ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array()
    }

    companion object {
        const val MAX_REVIEW_BATCH_SIZE = 200
        private const val REVIEW_SCAN_PAGE = 100
        private val PRIORITIES = setOf("P1", "P2", "P3")
        private val REVIEW_ACTIONS = setOf("CONFIRM", "REJECT", "SKIP")
        private val ACTIVE_TARGET_STATUSES = setOf("ACTIVE", "MITIGATING", "REOPENED")
    }
}
