package com.feedback.repository.cluster

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.cluster.Cluster
import com.feedback.model.cluster.ClusterLink
import com.feedback.model.feedback.FeedbackAi
import com.feedback.model.feedback.FeedbackContent
import com.feedback.model.feedback.FeedbackSource
import com.feedback.mapper.cluster.*
import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.math.BigDecimal

data class ClusterAccessFact(
    val sourceId: Long,
    val sourceChannel: String,
    val functionModule: String?,
    val provinceCode: String?,
    val cityCode: String?
)

data class ClusterChannelContext(
    val appVersion: String?,
    val osVersion: String?,
    val deviceModel: String?,
    val platform: String?
) {
    fun asMap(): Map<String, String?> = mapOf(
        "appVersion" to appVersion,
        "osVersion" to osVersion,
        "deviceModel" to deviceModel,
        "platform" to platform
    )
}

data class ClusterProfileFacts(
    val sources: Map<Long, FeedbackSource>,
    val contents: Map<Long, FeedbackContent>,
    val ai: Map<Long, FeedbackAi>,
    val identifiers: Map<Long, String>,
    val contexts: Map<Long, ClusterChannelContext>
)

data class ClusterShadowInputFacts(
    val sources: Map<Long, FeedbackSource>,
    val contents: Map<Long, FeedbackContent>
)

data class ClusterAiDecisionTask(
    val id: Long,
    val status: String,
    val inputSnapshot: String?,
    val outputSnapshot: String?
)

data class ClusterEvaluationRecord(
    val id: Long,
    val status: String,
    val model: String?,
    val summary: String?,
    val completedAt: LocalDateTime?
)

/** AI 归簇调用与离线评估共用 t_ai_task，但所有状态变更都限定各自 record/skill。 */
@Repository
class ClusterAiTaskRepository(private val mapper: ClusterAiTaskTableMapper) {
    fun decision(taskKey: String): ClusterAiDecisionTask? = mapper.findDecisionByTaskKey(taskKey)?.toDecision()

    fun enqueueDecision(
        sourceId: Long,
        taskKey: String,
        promptVersion: String,
        skillVersion: String,
        promptSha256: String,
        inputSnapshot: String,
        maxRetries: Int
    ): Boolean {
        val affected = mapper.insertDecision(ClusterAiDecisionTaskInsertPo(
            sourceId, taskKey, promptVersion, skillVersion, promptSha256, inputSnapshot,
            maxRetries.coerceIn(1, 10)
        ))
        ensureClusterRepositoryInvariant(affected in 0..1) { "CLUSTER_AI_ENQUEUE_AFFECTED_ROWS_INVALID" }
        return affected == 1
    }

    fun recoverExpired(): Int = mapper.recoverExpiredDecisions().also {
        ensureClusterRepositoryInvariant(it >= 0) { "CLUSTER_AI_RECOVERY_AFFECTED_ROWS_INVALID" }
    }

    fun dueIds(limit: Int): List<Long> {
        ensureClusterRepositoryRequest(limit in 1..100) { "CLUSTER_AI_DUE_LIMIT_INVALID" }
        return mapper.findDueDecisionIds(limit).also { ids ->
            ensureClusterRepositoryInvariant(ids.size <= limit && ids.distinct().size == ids.size && ids.zipWithNext().all { (a, b) -> a < b }) {
                "CLUSTER_AI_DUE_RESULT_INVALID"
            }
        }
    }

    fun claim(id: Long, holder: String): Boolean = mapper.claimDecision(id, holder).also {
        ensureClusterRepositoryInvariant(it in 0..1) { "CLUSTER_AI_CLAIM_AFFECTED_ROWS_INVALID" }
    } == 1

    fun decision(id: Long): ClusterAiDecisionTask? = mapper.findDecisionById(id)?.toDecision()

    fun complete(
        id: Long,
        holder: String,
        outputSnapshot: String,
        modelName: String?,
        providerRequestId: String?,
        inputTokens: Int?,
        outputTokens: Int?,
        latencyMs: Long?,
        estimatedCostUsd: BigDecimal?
    ) {
        val affected = mapper.completeDecision(ClusterAiDecisionCompletionPo(
            id, holder, outputSnapshot, modelName, providerRequestId, inputTokens, outputTokens,
            latencyMs, estimatedCostUsd
        ))
        ensureClusterRepositoryInvariant(affected in 0..1) { "CLUSTER_AI_COMPLETION_AFFECTED_ROWS_INVALID" }
    }

    fun fail(id: Long, holder: String, errorDetail: String) {
        val affected = mapper.failDecision(id, holder, errorDetail)
        ensureClusterRepositoryInvariant(affected in 0..1) { "CLUSTER_AI_FAILURE_AFFECTED_ROWS_INVALID" }
    }

    fun saveEvaluation(taskKey: String, passed: Boolean, input: String, output: String, actor: Long?, model: String) {
        ensureClusterRepositoryInvariant(mapper.insertEvaluation(ClusterEvaluationInsertPo(
            taskKey, if (passed) "COMPLETED" else "FAILED", input, output, actor, model
        )) == 1) { "CLUSTER_EVALUATION_INSERT_FAILED" }
    }

    fun latestEvaluation(): ClusterEvaluationRecord? = mapper.findLatestEvaluation()?.let {
        ClusterEvaluationRecord(it.id, it.taskStatus, it.modelNameSnapshot, it.outputSnapshot, it.completedAt)
    }

    private fun ClusterAiDecisionTaskPo.toDecision() = ClusterAiDecisionTask(
        id, taskStatus, inputSnapshot, outputSnapshot
    )
}

/**
 * 归簇画像事实的单表批量读取边界。
 *
 * 每张表对同一批 source ID 最多读取一次；重复行、非请求 ID 和超过 200 条都失败关闭，
 * 避免人工 split/batch review 在画像或权限校验中退化为 N+1。
 */
@Repository
class ClusterProfileFactRepository(
    private val sourceMapper: ClusterSourceTableMapper,
    private val contentMapper: ClusterContentTableMapper,
    private val aiMapper: ClusterAiTableMapper,
    private val sdkMapper: ClusterSdkContextTableMapper,
    private val jiraMapper: ClusterJiraContextTableMapper,
    private val qualityMapper: ClusterQualityContextTableMapper,
    private val redblackMapper: ClusterRedblackContextTableMapper
) {
    /** 影子全渠道读取不加载渠道扩展标识，且由 SQL 在返回前空化所有非 PASS 正文。 */
    fun loadShadowSafe(sourceIds: Collection<Long>): ClusterShadowInputFacts {
        val ids = boundedIds(sourceIds)
        if (ids.isEmpty()) return ClusterShadowInputFacts(emptyMap(), emptyMap())
        val sourceRows = checked(sourceMapper.findShadowSafeByIds(ids), ids, ClusterSourcePo::id, "CLUSTER_SHADOW_SOURCE")
        val contentRows = checked(
            contentMapper.findShadowSafeByIds(ids), ids, ClusterContentPo::sourceId, "CLUSTER_SHADOW_CONTENT"
        )
        ensureClusterRepositoryInvariant(sourceRows.keys.containsAll(ids)) { "CLUSTER_SHADOW_SOURCE_MISSING" }
        ensureClusterRepositoryInvariant(contentRows.keys.containsAll(ids)) { "CLUSTER_SHADOW_CONTENT_MISSING" }
        ensureClusterRepositoryInvariant(contentRows.values.all { row ->
            (row.maskingStatus == "PASS" && !row.isDeleted) ||
                listOf(row.title, row.description, row.functionModule, row.problemType, row.usageStage,
                    row.severityLevel, row.issueCategory).all { it == null }
        }) { "CLUSTER_SHADOW_RESTRICTED_CONTENT_EXPOSED" }
        return ClusterShadowInputFacts(
            sourceRows.mapValues { (_, row) -> row.toDomain() },
            contentRows.mapValues { (_, row) -> row.toDomain() }
        )
    }

    fun load(sourceIds: Collection<Long>): ClusterProfileFacts {
        val ids = boundedIds(sourceIds)
        if (ids.isEmpty()) return ClusterProfileFacts(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        val sourceRows = checked(sourceMapper.findByIds(ids), ids, ClusterSourcePo::id, "CLUSTER_SOURCE")
        val contentRows = checked(contentMapper.findByIds(ids), ids, ClusterContentPo::sourceId, "CLUSTER_CONTENT")
        val aiRows = checked(aiMapper.findByIds(ids), ids, ClusterAiPo::sourceId, "CLUSTER_AI")
        val sdk = checked(sdkMapper.findBySourceIds(ids), ids, ClusterSdkContextPo::sourceId, "CLUSTER_SDK")
        val jira = checked(jiraMapper.findBySourceIds(ids), ids, ClusterJiraContextPo::sourceId, "CLUSTER_JIRA")
        val quality = checked(qualityMapper.findBySourceIds(ids), ids, ClusterQualityContextPo::sourceId, "CLUSTER_QUALITY")
        val redblack = checked(redblackMapper.findBySourceIds(ids), ids, ClusterRedblackContextPo::sourceId, "CLUSTER_REDBLACK")
        val sources = sourceRows.mapValues { (_, row) -> row.toDomain() }
        val contents = contentRows.mapValues { (_, row) -> row.toDomain() }
        val aiFacts = aiRows.mapValues { (_, row) -> row.toDomain() }
        val identifiers = ids.mapNotNull { id ->
            val value = sdk[id]?.let { it.packageName ?: it.appId }
                ?: jira[id]?.projectKey
                ?: quality[id]?.applicationTypeRaw
                ?: redblack[id]?.sourcePlatform
                ?: sourceRows[id]?.packageName
            value?.trim()?.takeIf(String::isNotBlank)?.let { id to it }
        }.toMap()
        val contexts = ids.associateWith { id ->
            val source = sourceRows[id]
            ClusterChannelContext(
                appVersion = source?.appVersion,
                osVersion = sdk[id]?.osVersion,
                deviceModel = sdk[id]?.deviceModel,
                platform = sdk[id]?.let { it.packageName ?: it.appId }
                    ?: jira[id]?.projectKey ?: redblack[id]?.sourcePlatform
            )
        }
        return ClusterProfileFacts(sources, contents, aiFacts, identifiers, contexts)
    }

    fun accessFacts(sourceIds: Collection<Long>): Map<Long, ClusterAccessFact> {
        val facts = load(sourceIds)
        return facts.sources.mapNotNull { (id, source) ->
            val content = facts.contents[id] ?: return@mapNotNull null
            id to ClusterAccessFact(id, source.sourceChannel, content.functionModule,
                source.provinceCode, source.cityCode)
        }.toMap()
    }

    fun eligibleAfter(afterSourceId: Long, limit: Int): List<Long> {
        ensureClusterRepositoryRequest(afterSourceId >= 0) { "CLUSTER_SCHEDULER_CURSOR_INVALID" }
        ensureClusterRepositoryRequest(limit in 1..SCHEDULER_BATCH_SIZE) { "CLUSTER_SCHEDULER_BATCH_INVALID" }
        val rows = contentMapper.findEligibleAfter(afterSourceId, limit)
        ensureClusterRepositoryInvariant(rows.size <= limit) { "CLUSTER_SCHEDULER_MAPPER_OVERFLOW" }
        ensureClusterRepositoryInvariant(rows.map { it.sourceId }.zipWithNext().all { (left, right) -> left < right }) {
            "CLUSTER_SCHEDULER_ORDER_INVALID"
        }
        return rows.map { it.sourceId }
    }

    private fun boundedIds(values: Collection<Long>): List<Long> = values.distinct().sorted().also { ids ->
        ensureClusterRepositoryRequest(ids.size <= MAX_BATCH_SIZE) { "CLUSTER_PROFILE_BATCH_TOO_LARGE" }
        ensureClusterRepositoryRequest(ids.all { it > 0 }) { "CLUSTER_SOURCE_ID_INVALID" }
    }

    private fun <T> checked(rows: List<T>, ids: List<Long>, key: (T) -> Long, code: String): Map<Long, T> {
        val result = rows.associateBy(key)
        ensureClusterRepositoryInvariant(result.size == rows.size) { "${code}_DUPLICATED" }
        ensureClusterRepositoryInvariant(result.keys.all { it in ids }) { "${code}_UNEXPECTED_ID" }
        return result
    }

    companion object {
        const val MAX_BATCH_SIZE = 200
        const val SCHEDULER_BATCH_SIZE = 100
    }
}

data class ClusterLedgerClaim(
    val ledgerId: Long,
    val operation: String,
    val commandFingerprint: String,
    val completed: Boolean,
    val result: JsonNode?
)

/** requestId 命令账本复用现有唯一事实键；不需要新增迁移或旁路幂等表。 */
@Repository
class ClusterCommandLedgerRepository(
    private val mapper: ClusterCommandLedgerTableMapper,
    private val json: ObjectMapper
) {
    fun claim(requestId: String, operation: String, commandFingerprint: String, targetId: Long?): ClusterLedgerClaim {
        val factKey = sha256(requestId)
        val row = ClusterCommandLedgerPo(
            factKey = factKey,
            operation = operation,
            commandFingerprint = commandFingerprint,
            targetId = targetId,
            requestId = requestId
        )
        val affected = mapper.claim(row)
        ensureClusterRepositoryInvariant(affected in 0..2) { "CLUSTER_COMMAND_LEDGER_AFFECTED_ROWS_INVALID" }
        val persisted = mapper.findByFactKey(factKey) ?: clusterRepositoryInvariantViolation("CLUSTER_COMMAND_LEDGER_MISSING")
        val id = persisted.id ?: clusterRepositoryInvariantViolation("CLUSTER_COMMAND_LEDGER_ID_MISSING")
        ensureClusterRepositoryInvariant(persisted.factKey == factKey && persisted.requestId == requestId) {
            "CLUSTER_COMMAND_LEDGER_UNEXPECTED_RESULT"
        }
        ensureClusterRepositoryInvariant(persisted.factStatus in setOf("PENDING", "COMPLETED")) {
            "CLUSTER_COMMAND_LEDGER_STATUS_INVALID"
        }
        return ClusterLedgerClaim(
            ledgerId = id,
            operation = persisted.operation,
            commandFingerprint = persisted.commandFingerprint,
            completed = persisted.factStatus == "COMPLETED",
            result = persisted.evaluationSnapshot?.let(json::readTree)
        )
    }

    fun complete(ledgerId: Long, requestId: String, result: Map<String, Any?>) {
        val snapshot = json.writeValueAsString(linkedMapOf("requestId" to requestId) + result)
        ensureClusterRepositoryInvariant(mapper.complete(ledgerId, snapshot) == 1) { "CLUSTER_COMMAND_LEDGER_COMPLETE_CONFLICT" }
    }

    fun recordDecision(row: ClusterDecisionFactPo) {
        ensureClusterRepositoryInvariant(mapper.insertDecision(row) == 1) { "CLUSTER_DECISION_FACT_INSERT_FAILED" }
    }

    fun fingerprint(vararg values: Any?): String = sha256(values.joinToString("\u001f") { value ->
        when (value) {
            is Iterable<*> -> value.joinToString("\u001e")
            else -> value?.toString().orEmpty()
        }
    })

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Cluster 与 ClusterLink 的唯一业务持久化边界；所有 SQL 位于对应单表 Mapper XML。 */
@Repository
class ClusterMutationRepository(
    private val clusters: ClusterTableMapper,
    private val links: ClusterLinkTableMapper,
    private val aiTasks: ClusterAiTaskTableMapper,
    private val reclassificationRuns: ClusterReclassificationRunTableMapper
) {
    fun clusters(ids: Collection<Long>): List<Cluster> {
        val normalized = boundedIds(ids, MAX_CLUSTER_BATCH, "CLUSTER_ID")
        if (normalized.isEmpty()) return emptyList()
        return checked(clusters.findByIds(normalized), normalized, ClusterPo::id, "CLUSTER").values.map(ClusterPo::toDomain)
    }

    fun cluster(id: Long): Cluster? = clusters(listOf(id)).singleOrNull()

    fun lockClusters(ids: Collection<Long>): List<Cluster> {
        val normalized = boundedIds(ids, MAX_CLUSTER_LOCK_BATCH, "CLUSTER_ID")
        ensureClusterRepositoryRequest(normalized.isNotEmpty()) { "CLUSTER_IDS_REQUIRED" }
        val rows = clusters.lockByIds(normalized)
        val result = checked(rows, normalized, ClusterPo::id, "CLUSTER_LOCK")
        if (result.size != normalized.size) throw ClientException(ErrorCode.NOT_FOUND, "CLUSTER_NOT_FOUND")
        ensureClusterRepositoryInvariant(rows.map { it.id } == normalized) { "CLUSTER_LOCK_ORDER_INVALID" }
        return rows.map(ClusterPo::toDomain)
    }

    fun byNameKeys(nameKeys: Collection<String>, lock: Boolean = false): List<Cluster> {
        val keys = nameKeys.distinct().sorted()
        ensureClusterRepositoryRequest(keys.size <= MAX_CLUSTER_BATCH) { "CLUSTER_NAME_BATCH_TOO_LARGE" }
        if (keys.isEmpty()) return emptyList()
        val rows = if (lock) clusters.lockByNameKeys(keys, OPERATIONAL_WITH_RESOLVED)
        else clusters.findByNameKeys(keys, OPERATIONAL_WITH_RESOLVED)
        ensureClusterRepositoryInvariant(rows.map { it.id }.distinct().size == rows.size) { "CLUSTER_NAME_RESULT_DUPLICATED" }
        ensureClusterRepositoryInvariant(rows.all { it.clusterNameKey in keys }) { "CLUSTER_NAME_RESULT_UNEXPECTED" }
        return rows.map(ClusterPo::toDomain)
    }

    fun candidatesByProduct(productScope: String, limit: Int = 100): List<Cluster> =
        clusters.findByProductScopeAndStatuses(productScope, OPERATIONAL_WITH_RESOLVED, limit)
            .validatedClusterRows(limit)

    fun candidatesBySignature(productScope: String, signature: String, limit: Int = 200): List<Cluster> =
        clusters.findByProductScopeSignatureAndStatuses(productScope, signature, OPERATIONAL_WITH_RESOLVED, limit)
            .validatedClusterRows(limit)

    fun candidatesByExactProfile(productScope: String, module: String?, category: String?, symptom: String, limit: Int = 200): List<Cluster> =
        clusters.findByExactProfile(productScope, module, category, symptom, OPERATIONAL_WITH_RESOLVED, limit)
            .validatedClusterRows(limit)

    fun candidatesByFingerprint(fingerprint: String, limit: Int = 200): List<Cluster> {
        val ids = links.findClusterIdsByProblemFingerprint(fingerprint, limit)
        ensureClusterRepositoryInvariant(ids.size <= limit && ids.distinct().size == ids.size) { "CLUSTER_FINGERPRINT_RESULT_INVALID" }
        return clusters(ids)
    }

    fun insertOrReuse(row: ClusterInsertPo): Pair<Cluster, Boolean> {
        val affected = clusters.insertOrReuse(row)
        ensureClusterRepositoryInvariant(affected in 0..2) { "CLUSTER_INSERT_AFFECTED_ROWS_INVALID" }
        val id = row.id ?: clusterRepositoryInvariantViolation("CLUSTER_INSERT_ID_MISSING")
        val persisted = cluster(id) ?: clusterRepositoryInvariantViolation("CLUSTER_INSERT_RESULT_MISSING")
        // Connector/J 的 found-rows 配置会让重复键复用也可能返回 1，不能用 affected rows 判定新建。
        return persisted to persisted.publicId.contentEquals(row.publicId)
    }

    fun insertChildren(rows: List<ClusterInsertPo>): List<Cluster> {
        ensureClusterRepositoryRequest(rows.size in 2..MAX_CLUSTER_BATCH) { "CLUSTER_CHILD_BATCH_INVALID" }
        ensureClusterRepositoryRequest(rows.map { it.clusterNameKey }.distinct().size == rows.size) { "CLUSTER_CHILD_NAME_DUPLICATED" }
        ensureClusterRepositoryInvariant(clusters.insertAll(rows) == rows.size) { "CLUSTER_CHILD_INSERT_AFFECTED_ROWS_INVALID" }
        val persisted = byNameKeys(rows.map { it.clusterNameKey })
        ensureClusterRepositoryInvariant(persisted.size == rows.size) { "CLUSTER_CHILD_INSERT_RESULT_MISMATCH" }
        return persisted
    }

    fun updateStatus(row: ClusterStatusCasPo) {
        ensureClusterRepositoryInvariant(clusters.updateStatusCas(row) == 1, ErrorCode.CLUSTER_REVIEW_CONFLICT) { "CLUSTER_LIFECYCLE_VERSION_CONFLICT" }
    }

    fun updateStatuses(rows: List<ClusterStatusCasPo>) {
        if (rows.isEmpty()) return
        ensureClusterRepositoryRequest(rows.size <= MAX_CLUSTER_BATCH) { "CLUSTER_STATUS_BATCH_TOO_LARGE" }
        ensureClusterRepositoryRequest(rows.map { it.id }.distinct().size == rows.size) { "CLUSTER_STATUS_ID_DUPLICATED" }
        ensureClusterRepositoryInvariant(clusters.updateStatusesCas(rows) == rows.size, ErrorCode.CLUSTER_REVIEW_CONFLICT) { "CLUSTER_LIFECYCLE_VERSION_CONFLICT" }
    }

    fun bumpLifecycle(row: ClusterStatusCasPo) {
        ensureClusterRepositoryInvariant(clusters.bumpLifecycleCas(row) == 1, ErrorCode.CLUSTER_REVIEW_CONFLICT) { "CLUSTER_LIFECYCLE_VERSION_CONFLICT" }
    }

    fun reopenResolved(id: Long, expectedVersion: Long, reason: String): Boolean {
        val affected = clusters.reopenResolved(id, expectedVersion, reason)
        ensureClusterRepositoryInvariant(affected in 0..1) { "CLUSTER_REOPEN_AFFECTED_ROWS_INVALID" }
        return affected == 1
    }

    fun updateStatistics(rows: List<ClusterStatisticsUpdatePo>) {
        if (rows.isEmpty()) return
        ensureClusterRepositoryRequest(rows.size <= MAX_CLUSTER_BATCH) { "CLUSTER_STATISTICS_BATCH_TOO_LARGE" }
        ensureClusterRepositoryInvariant(clusters.updateStatistics(rows) == rows.size) { "CLUSTER_STATISTICS_AFFECTED_ROWS_INVALID" }
    }

    fun evolveName(id: Long, expectedVersion: Long, oldName: String, newName: String, nameKey: String): Boolean {
        val affected = clusters.evolveName(id, expectedVersion, oldName, newName, nameKey)
        ensureClusterRepositoryInvariant(affected in 0..1) { "CLUSTER_NAME_EVOLUTION_AFFECTED_ROWS_INVALID" }
        return affected == 1
    }

    fun clusterIdsAfter(afterId: Long, limit: Int): List<Long> = clusters.findIdsAfter(afterId, limit).also { ids ->
        ensureClusterRepositoryInvariant(ids.size <= limit && ids.zipWithNext().all { (a, b) -> a < b }) { "CLUSTER_ID_PAGE_INVALID" }
    }

    fun linksBySources(sourceIds: Collection<Long>, lock: Boolean = false): List<ClusterLink> {
        val ids = boundedIds(sourceIds, MAX_SOURCE_BATCH, "CLUSTER_SOURCE")
        if (ids.isEmpty()) return emptyList()
        val rows = if (lock) links.lockBySourceIds(ids) else links.findBySourceIds(ids)
        ensureClusterRepositoryInvariant(rows.map { it.id }.distinct().size == rows.size) { "CLUSTER_LINK_RESULT_DUPLICATED" }
        ensureClusterRepositoryInvariant(rows.all { it.feedbackSourceId in ids }) { "CLUSTER_LINK_RESULT_UNEXPECTED" }
        ensureClusterRepositoryInvariant(!lock || rows.zipWithNext().all { (a, b) ->
            a.feedbackSourceId < b.feedbackSourceId ||
                (a.feedbackSourceId == b.feedbackSourceId && a.id < b.id)
        }) { "CLUSTER_LINK_LOCK_ORDER_INVALID" }
        return rows.map(ClusterLinkPo::toDomain)
    }

    fun activeMainBySources(sourceIds: Collection<Long>, lock: Boolean = false): Map<Long, ClusterLink> {
        val rows = linksBySources(sourceIds, lock).filter { it.isActive && it.relationRole == "MAIN" }
        val result = rows.associateBy(ClusterLink::feedbackSourceId)
        ensureClusterRepositoryInvariant(result.size == rows.size) { "CLUSTER_ACTIVE_MAIN_DUPLICATED" }
        return result
    }

    fun activeMainsByClusters(clusterIds: Collection<Long>): List<ClusterLink> {
        val result = mutableListOf<ClusterLink>()
        visitActiveMainPages(clusterIds) { result += it }
        return result
    }

    /** 大目标簇按 200 条 link 分页读取，调用方必须在每页内完成权限或统计组装。 */
    fun visitActiveMainPages(clusterIds: Collection<Long>, visitor: (List<ClusterLink>) -> Unit) {
        val ids = boundedIds(clusterIds, MAX_CLUSTER_BATCH, "CLUSTER_ID")
        if (ids.isEmpty()) return
        var after = 0L
        while (true) {
            val page = links.findActiveMainByClusterIdsAfter(ids, after, PAGE_SIZE)
            ensureClusterRepositoryInvariant(page.size <= PAGE_SIZE) { "CLUSTER_MAIN_PAGE_OVERFLOW" }
            ensureClusterRepositoryInvariant(page.all { it.clusterId in ids && it.id > after }) { "CLUSTER_MAIN_PAGE_UNEXPECTED" }
            ensureClusterRepositoryInvariant(page.zipWithNext().all { (a, b) -> a.id < b.id }) { "CLUSTER_MAIN_PAGE_ORDER_INVALID" }
            visitor(page.map(ClusterLinkPo::toDomain))
            if (page.size < PAGE_SIZE) break
            after = page.last().id
        }
    }

    fun lockActiveMainsByClusters(clusterIds: Collection<Long>, maximum: Int): List<ClusterLink> {
        val ids = boundedIds(clusterIds, MAX_CLUSTER_BATCH, "CLUSTER_ID")
        if (ids.isEmpty()) return emptyList()
        val rows = links.lockActiveMainByClusterIds(ids, maximum + 1)
        ensureClusterRepositoryRequest(rows.size <= maximum) { "CLUSTER_AFFECTED_SOURCE_LIMIT_EXCEEDED" }
        ensureClusterRepositoryInvariant(rows.map { it.id }.distinct().size == rows.size) { "CLUSTER_MAIN_LINK_DUPLICATED" }
        ensureClusterRepositoryInvariant(rows.all { it.clusterId in ids }) { "CLUSTER_MAIN_LINK_UNEXPECTED" }
        ensureClusterRepositoryInvariant(rows.zipWithNext().all { (a, b) ->
            a.feedbackSourceId < b.feedbackSourceId ||
                (a.feedbackSourceId == b.feedbackSourceId && a.id < b.id)
        }) { "CLUSTER_MAIN_LINK_LOCK_ORDER_INVALID" }
        return rows.map(ClusterLinkPo::toDomain)
    }

    fun pendingReviewSourcesAfter(
        afterPriority: String?, afterDueAt: LocalDateTime?, afterSourceId: Long, priority: String?, limit: Int
    ): List<ClusterReviewQueuePo> =
        links.findPendingReviewSourcesAfter(afterPriority, afterDueAt, afterSourceId, priority, limit).also { rows ->
            ensureClusterRepositoryInvariant(rows.size <= limit && rows.map { it.feedbackSourceId }.distinct().size == rows.size) {
                "CLUSTER_REVIEW_PAGE_INVALID"
            }
            ensureClusterRepositoryInvariant(rows.zipWithNext().all { (left, right) ->
                compareValuesBy(left, right,
                    { reviewPriorityRank(it.priority) },
                    ClusterReviewQueuePo::dueAt,
                    ClusterReviewQueuePo::feedbackSourceId) < 0
            }) { "CLUSTER_REVIEW_PAGE_ORDER_INVALID" }
        }

    fun pendingCandidates(sourceIds: Collection<Long>): List<ClusterLink> {
        val ids = boundedIds(sourceIds, MAX_SOURCE_BATCH, "CLUSTER_SOURCE")
        if (ids.isEmpty()) return emptyList()
        val rows = links.findCandidatesBySourceIds(ids)
        ensureClusterRepositoryInvariant(rows.map { it.id }.distinct().size == rows.size) { "CLUSTER_CANDIDATE_DUPLICATED" }
        ensureClusterRepositoryInvariant(rows.all { it.feedbackSourceId in ids }) { "CLUSTER_CANDIDATE_UNEXPECTED" }
        ensureClusterRepositoryInvariant(rows.zipWithNext().all { (left, right) ->
            left.feedbackSourceId < right.feedbackSourceId ||
                (left.feedbackSourceId == right.feedbackSourceId &&
                    (left.candidateRank ?: Int.MAX_VALUE) <= (right.candidateRank ?: Int.MAX_VALUE))
        }) { "CLUSTER_CANDIDATE_ORDER_INVALID" }
        return rows.map(ClusterLinkPo::toDomain)
    }

    fun rejectedClusterIds(sourceId: Long): Set<Long> = links.findRejectedClusterIds(sourceId).also { ids ->
        ensureClusterRepositoryInvariant(ids.distinct().size == ids.size) { "CLUSTER_REJECTED_RESULT_DUPLICATED" }
    }.toSet()

    fun insertLinks(rows: List<ClusterLinkInsertPo>) {
        if (rows.isEmpty()) return
        ensureClusterRepositoryRequest(rows.size <= MAX_SOURCE_BATCH) { "CLUSTER_LINK_INSERT_BATCH_TOO_LARGE" }
        ensureClusterRepositoryRequest(rows.map { it.feedbackSourceId to it.relationRole }.distinct().size == rows.size) {
            "CLUSTER_LINK_INSERT_DUPLICATED"
        }
        ensureClusterRepositoryInvariant(links.insertAll(rows) == rows.size) { "CLUSTER_LINK_INSERT_AFFECTED_ROWS_INVALID" }
    }

    fun claimCandidates(rows: List<ClusterCandidateClaimPo>, expectedRows: Int) {
        if (rows.isEmpty()) return
        ensureClusterRepositoryRequest(rows.size <= MAX_SOURCE_BATCH) { "CLUSTER_REVIEW_BATCH_TOO_LARGE" }
        ensureClusterRepositoryRequest(rows.map { it.sourceId }.distinct().size == rows.size) { "CLUSTER_REVIEW_SOURCE_DUPLICATED" }
        ensureClusterRepositoryRequest(expectedRows >= rows.size) { "CLUSTER_REVIEW_EXPECTED_ROWS_INVALID" }
        ensureClusterRepositoryInvariant(links.claimCandidates(rows) == expectedRows, ErrorCode.CLUSTER_REVIEW_CONFLICT) { "CLUSTER_REVIEW_VERSION_CONFLICT" }
    }

    fun deactivateMainLinks(rows: List<ClusterMainDeactivatePo>) {
        if (rows.isEmpty()) return
        ensureClusterRepositoryRequest(rows.size <= MAX_SOURCE_BATCH) { "CLUSTER_MAIN_DEACTIVATE_BATCH_TOO_LARGE" }
        ensureClusterRepositoryRequest(rows.map { it.linkId }.distinct().size == rows.size) { "CLUSTER_MAIN_LINK_DUPLICATED" }
        ensureClusterRepositoryInvariant(links.deactivateMainLinks(rows) == rows.size, ErrorCode.CLUSTER_MAIN_RACE) { "CLUSTER_MAIN_LINK_CAS_CONFLICT" }
    }

    fun skipCandidates(rows: List<ClusterCandidateSkipPo>, reason: String) {
        if (rows.isEmpty()) return
        ensureClusterRepositoryRequest(rows.size <= MAX_SOURCE_BATCH) { "CLUSTER_CANDIDATE_SKIP_BATCH_TOO_LARGE" }
        ensureClusterRepositoryRequest(rows.map { it.linkId }.distinct().size == rows.size) { "CLUSTER_CANDIDATE_SKIP_LINK_DUPLICATED" }
        ensureClusterRepositoryInvariant(links.skipCandidates(rows, reason) == rows.size) {
            "CLUSTER_CANDIDATE_SKIP_CAS_CONFLICT"
        }
    }

    fun updateProblemFingerprint(sourceId: Long, clusterId: Long, fingerprint: String) {
        ensureClusterRepositoryInvariant(links.updateProblemFingerprint(sourceId, clusterId, fingerprint) == 1) {
            "CLUSTER_MAIN_FINGERPRINT_AFFECTED_ROWS_INVALID"
        }
    }

    fun appendNameEvolution(sourceId: Long, clusterId: Long, from: String, to: String) {
        ensureClusterRepositoryInvariant(links.appendNameEvolution(sourceId, clusterId, from, to) == 1) {
            "CLUSTER_NAME_EVIDENCE_AFFECTED_ROWS_INVALID"
        }
    }

    fun hasActiveReclassification(): Boolean = reclassificationRuns.findActiveId() != null

    fun pendingAiSourceIds(sourceIds: List<Long>): Set<Long> {
        if (sourceIds.isEmpty()) return emptySet()
        val ids = aiTasks.findPendingSourceIds(sourceIds)
        ensureClusterRepositoryInvariant(ids.distinct().size == ids.size && ids.all { it in sourceIds }) { "CLUSTER_AI_PENDING_RESULT_INVALID" }
        return ids.toSet()
    }

    private fun List<ClusterPo>.validatedClusterRows(limit: Int): List<Cluster> {
        ensureClusterRepositoryInvariant(size <= limit && map { it.id }.distinct().size == size) { "CLUSTER_CANDIDATE_RESULT_INVALID" }
        return map(ClusterPo::toDomain)
    }

    private fun boundedIds(values: Collection<Long>, maximum: Int, code: String): List<Long> =
        values.distinct().sorted().also { ids ->
            ensureClusterRepositoryRequest(ids.size <= maximum) { "${code}_BATCH_TOO_LARGE" }
            ensureClusterRepositoryRequest(ids.all { it > 0 }) { "${code}_ID_INVALID" }
        }

    private fun <T> checked(rows: List<T>, requested: List<Long>, key: (T) -> Long, code: String): Map<Long, T> {
        val result = rows.associateBy(key)
        ensureClusterRepositoryInvariant(result.size == rows.size) { "${code}_RESULT_DUPLICATED" }
        ensureClusterRepositoryInvariant(result.keys.all { it in requested }) { "${code}_RESULT_UNEXPECTED" }
        return result
    }

    private fun reviewPriorityRank(priority: String): Int =
        when (priority) { "P1" -> 1; "P2" -> 2; "P3" -> 3; else -> 4 }

    companion object {
        const val MAX_SOURCE_BATCH = 200
        const val MAX_CLUSTER_BATCH = 200
        const val MAX_CLUSTER_LOCK_BATCH = 201
        const val PAGE_SIZE = 200
        val OPERATIONAL_WITH_RESOLVED = listOf("ACTIVE", "MITIGATING", "REOPENED", "RESOLVED")
    }
}

private fun ClusterPo.toDomain() = Cluster(
    id = id, publicId = publicId, clusterName = clusterName, clusterNameKey = clusterNameKey,
    productScope = productScope, functionModule = functionModule, issueCategory = issueCategory,
    canonicalSymptom = canonicalSymptom, canonicalObject = canonicalObject,
    canonicalTrigger = canonicalTrigger, errorSignature = errorSignature,
    totalRecordCount = totalRecordCount, channelDistribution = channelDistribution,
    clusterStatus = clusterStatus, lifecycleVersion = lifecycleVersion, statisticsVersion = statisticsVersion,
    statisticsReconciledAt = statisticsReconciledAt, mergedIntoClusterId = mergedIntoClusterId,
    splitFromClusterId = splitFromClusterId, statusChangedBy = statusChangedBy,
    statusChangedAt = statusChangedAt, statusReason = statusReason,
    vectorIndexStatus = vectorIndexStatus, searchIndexStatus = searchIndexStatus,
    createdAt = createdAt, updatedAt = updatedAt
)

private fun ClusterLinkPo.toDomain() = ClusterLink(
    id = id, clusterId = clusterId, feedbackSourceId = feedbackSourceId, relationRole = relationRole,
    linkType = linkType, matchSimilarity = matchSimilarity, candidateRank = candidateRank,
    matchEvidence = matchEvidence, decisionVersion = decisionVersion,
    decisionConfigVersion = decisionConfigVersion, featureSnapshot = featureSnapshot,
    problemFingerprint = problemFingerprint, reviewStatus = reviewStatus, reviewPriority = reviewPriority,
    reviewDueAt = reviewDueAt, reviewVersion = reviewVersion, reviewedBy = reviewedBy,
    reviewedAt = reviewedAt, reviewReason = reviewReason, reviewRequestId = reviewRequestId,
    decisionReason = decisionReason, reclassificationRunId = reclassificationRunId,
    supersededByReclassificationRunId = supersededByReclassificationRunId,
    isActive = isActive, createdAt = createdAt
)

private fun ClusterSourcePo.toDomain() = FeedbackSource(
    id = id, publicId = publicId, sourceChannel = sourceChannel, sourceRecordId = sourceRecordId,
    appVersion = appVersion, packageName = packageName, sourceEventTime = sourceEventTime,
    sourceUpdateTime = updatedAt, ingestTime = ingestTime, provinceCode = provinceCode,
    cityCode = cityCode, createdAt = createdAt, updatedAt = updatedAt
)

private fun ClusterContentPo.toDomain() = FeedbackContent(
    sourceId = sourceId, title = title, description = description, functionModule = functionModule,
    problemType = problemType, usageStage = usageStage, severityLevel = severityLevel,
    issueCategory = issueCategory, maskingStatus = maskingStatus, isDeleted = isDeleted,
    createdAt = createdAt, updatedAt = updatedAt
)

private fun ClusterAiPo.toDomain() = FeedbackAi(
    sourceId = sourceId, aiSummary = aiSummary, aiConfidence = aiConfidence, reviewStatus = reviewStatus
)
