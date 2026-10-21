package com.feedback.service.jira

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.po.jira.owner.JiraOwnerInitializationWritePo
import com.feedback.repository.jiraowner.JiraOwnerInitializationRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class JiraOwnerInitializationResult(
    val requestedCount: Int,
    val initializedSourceIds: List<Long>,
    val skippedSourceIds: List<Long>
)

/**
 * 将 Jira 来源快照中的唯一有效经办人首次映射为平台负责人。
 *
 * 本用例只初始化尚未分配且 workflow 仍为 UNASSIGNED 的 Jira 反馈，绝不覆盖平台分发或交接形成的负责人。
 * assignment、workflow、处置审计及投影脏标记必须在同一权威事务中成功；任一影响行不符合预期即整批回滚。
 */
@Service
class JiraAssigneeOwnerInitializer(
    private val repository: JiraOwnerInitializationRepository,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker
) {
    fun findBackfillPage(afterSourceId: Long, limit: Int = JiraOwnerInitializationRepository.MAX_BATCH_SIZE): List<Long> =
        repository.findJiraSourcePage(afterSourceId, limit).map { it.id }

    @Transactional
    fun initialize(sourceId: Long): Boolean = initializeWithinTransaction(listOf(sourceId))
        .initializedSourceIds.contains(sourceId)

    @Transactional
    fun initializeBatch(sourceIds: List<Long>): JiraOwnerInitializationResult =
        initializeWithinTransaction(sourceIds)

    private fun initializeWithinTransaction(sourceIds: List<Long>): JiraOwnerInitializationResult {
        val ids = sourceIds.distinct().sorted()
        if(ids.isEmpty() || ids.size > JiraOwnerInitializationRepository.MAX_BATCH_SIZE || ids.any { it <= 0 }) {
            throw ClientException(ErrorCode.BAD_REQUEST, "JIRA_OWNER_SOURCE_IDS_INVALID")
        }

        val jiraSourceIds = repository.findJiraSources(ids).mapTo(mutableSetOf()) { it.id }
        val assigneeBySourceId = repository.findAssignees(ids).associate { it.sourceId to it.assignee }

        // 与人工分发路径保持 assignment -> workflow 的固定锁序，所有批次内部再按 source ID 升序。
        // 即使 assignment 尚不存在，唯一键插入和严格影响行检查仍会关闭并发抢占，绝不覆盖既有负责人。
        val assignedSourceIds = repository.lockAssignments(ids).mapTo(mutableSetOf()) { it.feedbackSourceId }
        val workflowBySourceId = repository.lockWorkflows(ids).associateBy { it.feedbackSourceId }
        val candidateNames = ids.mapNotNull { sourceId ->
            assigneeBySourceId[sourceId]?.takeIf { it.trim().isNotEmpty() }
        }.distinct()
        val candidateSet = if (candidateNames.isEmpty()) {
            null
        } else {
            repository.findActiveUserCandidates(candidateNames)
        }
        // 401 行哨兵说明至少一个候选键的完整集合无法在本批有界读取，必须整体跳过，不能以截断结果判唯一。
        if (candidateSet?.overflowed == true) return JiraOwnerInitializationResult(ids.size, emptyList(), ids)
        val candidateGroupByRequestedName = candidateSet?.groups.orEmpty()
            .flatMap { group -> group.requestedDisplayNames.map { it to group } }
            .toMap()

        val writes = ids.mapNotNull { sourceId ->
            if (sourceId !in jiraSourceIds || sourceId in assignedSourceIds) return@mapNotNull null
            val workflow = workflowBySourceId[sourceId] ?: return@mapNotNull null
            if (workflow.distributionStatus != "UNASSIGNED") return@mapNotNull null
            val assignee = assigneeBySourceId[sourceId]?.takeIf { it.trim().isNotEmpty() } ?: return@mapNotNull null
            val owner = candidateGroupByRequestedName[assignee]?.activeUsers?.singleOrNull() ?: return@mapNotNull null
            JiraOwnerInitializationWritePo(sourceId, owner.id)
        }

        if (writes.isEmpty()) return JiraOwnerInitializationResult(ids.size, emptyList(), ids)

        repository.insertAssignments(writes)
        repository.acceptWorkflows(writes)
        repository.insertDispositionEvents(writes)
        val initializedIds = writes.map(JiraOwnerInitializationWritePo::sourceId)
        dirtyMarker.mark(
            FeedbackListProjectionDirtyCommand(initializedIds, FeedbackListProjectionDirtyReason.ASSIGNMENT)
        )
        dirtyMarker.mark(
            FeedbackListProjectionDirtyCommand(initializedIds, FeedbackListProjectionDirtyReason.WORKFLOW)
        )
        return JiraOwnerInitializationResult(ids.size, initializedIds, ids - initializedIds.toSet())
    }

    companion object {
        const val MAX_BATCH_SIZE = JiraOwnerInitializationRepository.MAX_BATCH_SIZE
    }
}

/**
 * 启动回填使用 source ID keyset 分页，每页最多 200 条。批次异常时停止且不越过失败页，
 * 避免把数据库、事务或 Marker 故障静默降级为“已处理”。下一次启动会从头幂等重试。
 */
@Component
@ConditionalOnProperty(
    prefix = "app.jira",
    name = ["assignee-owner-backfill-enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class JiraAssigneeOwnerBackfillRunner(private val initializer: JiraAssigneeOwnerInitializer) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        var cursor = 0L
        var scanned = 0
        var initialized = 0
        while (true) {
            val sourceIds = initializer.findBackfillPage(cursor)
            if (sourceIds.isEmpty()) break
            val result = try {
                initializer.initializeBatch(sourceIds)
            } catch (error: Exception) {
                log.warn(
                    "Jira owner initialization stopped: code=JIRA_OWNER_INITIALIZATION_BATCH_FAILED cursor={} batchSize={} errorType={}",
                    cursor,
                    sourceIds.size,
                    error.javaClass.simpleName
                )
                return
            }
            scanned += result.requestedCount
            initialized += result.initializedSourceIds.size
            cursor = sourceIds.last()
        }
        log.info("Jira owner initialization completed: scanned={} initialized={}", scanned, initialized)
    }
}
