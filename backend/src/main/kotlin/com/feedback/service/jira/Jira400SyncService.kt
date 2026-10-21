package com.feedback.service.jira

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import com.feedback.common.exception.ServiceException
import com.feedback.config.Jira400Properties
import com.feedback.repository.jira.JiraSyncStateRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class JiraSyncStatusView(
    val enabled: Boolean,
    val ready: Boolean,
    val readinessErrors: List<String>,
    val status: String,
    val watermark: LocalDateTime?,
    val lastAttemptAt: LocalDateTime?,
    val lastSuccessAt: LocalDateTime?,
    val nextRunAt: LocalDateTime?,
    val lastBatchId: String?,
    val failureCount: Int,
    val lastErrorCode: String?,
    val lastErrorDetail: String?,
    val attachmentPendingCount: Long = 0,
    val attachmentFailedCount: Long = 0
)

data class JiraSyncEnqueueResult(val accepted: Boolean, val status: JiraSyncStatusView)
data class JiraFormalReviewResult(
    val accepted: Boolean,
    val batchId: String?,
    val fetchedKeys: List<String>,
    val missingKeys: List<String>,
    val status: JiraSyncStatusView
)

@Service
class Jira400SyncService(
    private val properties: Jira400Properties,
    private val gateway: Jira400Gateway,
    private val issueMapper: JiraIssueMapper,
    private val states: JiraSyncStateRepository,
    private val importPort: JiraSyncImportPort,
    private val clock: Clock,
    private val attachmentQueue:JiraAttachmentPullQueue?=null
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = "jira400-${UUID.randomUUID()}"

    fun status(): JiraSyncStatusView {
        ensureState()
        val state = states.get(properties.connectionKey)
        val errors = readinessErrors()
        val attachmentSummary=attachmentQueue?.summary().orEmpty()
        return JiraSyncStatusView(
            enabled = properties.enabled, ready = errors.isEmpty(), readinessErrors = errors,
            status = state?.status ?: if (properties.enabled) "IDLE" else "DISABLED", watermark = state?.watermark,
            lastAttemptAt = state?.lastAttemptAt, lastSuccessAt = state?.lastSuccessAt, nextRunAt = state?.nextRunAt,
            lastBatchId = state?.lastBatchId?.let(importPort::publicBatchId), failureCount = state?.failureCount ?: 0,
            lastErrorCode = state?.lastErrorCode, lastErrorDetail = state?.lastErrorDetail,
            attachmentPendingCount=attachmentSummary["pending"]?:0,attachmentFailedCount=attachmentSummary["failed"]?:0
        )
    }

    fun enqueue(trigger: String, from: LocalDateTime?, to: LocalDateTime?, actor: Long?): JiraSyncEnqueueResult {
        val errors = readinessErrors()
        if(errors.isNotEmpty()) unavailable("JIRA_SYNC_NOT_READY")
        if(trigger !in setOf("MANUAL", "BACKFILL")) invalid("JIRA_TRIGGER_TYPE_INVALID")
        if(from != null && to != null && from.isAfter(to)) invalid("JIRA_SYNC_WINDOW_INVALID")
        ensureState()
        val accepted = states.enqueue(properties.connectionKey, trigger, from, to, actor)
        return JiraSyncEnqueueResult(accepted, status())
    }

    fun rePullForFormalReview(keys: List<String>, actor: Long?): JiraFormalReviewResult {
        val errors = readinessErrors()
        if(errors.isNotEmpty()) unavailable("JIRA_SYNC_NOT_READY")
        val normalizedKeys = keys.map { it.trim().uppercase() }.filter(String::isNotBlank).distinct()
        if(normalizedKeys.isEmpty() || normalizedKeys.size>100) invalid("JIRA_REVIEW_KEYS_INVALID")
        if(normalizedKeys.any { !it.matches(Regex("^[A-Z][A-Z0-9]+-[0-9]+$")) }) invalid("JIRA_ISSUE_KEY_INVALID")

        val contract = importPort.activeContract()

        // Jira Server 把 `key` 与 `id` 当作排序别名，同时按两者排序会被拒绝。
        val jql = "(${properties.jql}) AND key IN (${normalizedKeys.joinToString(",") { "'$it'" }}) ORDER BY id ASC"
        val issues = enrichProjects(fetchAll(jql, contract.requestedFields, maintainLease = false))
        val fetchedKeys = issues.map { it.key }.toSet()
        val missingKeys = normalizedKeys.filter { it !in fetchedKeys }
        val mappedRows = issues.map { issueMapper.toMappedSourceRow(it, contract) }

        val now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(properties.timeZone))
        val batchView = if (mappedRows.isEmpty()) null else importPort.stage(
            mappedRows, contract, "FORMAL_REVIEW", now.minusDays(1), now.plusDays(1), instanceHash(), actor
        )
        if (missingKeys.isNotEmpty()) importPort.markFormalReviewUnavailable(missingKeys)
        return JiraFormalReviewResult(
            accepted = batchView != null || missingKeys.isNotEmpty(),
            batchId = batchView?.batchId,
            fetchedKeys = fetchedKeys.toList().sorted(),
            missingKeys = missingKeys.sorted(),
            status = status()
        )
    }

    fun rePullForFaultTimeRepair(keys:List<String>,actor:Long?):JiraFormalReviewResult {
        val errors=readinessErrors();if(errors.isNotEmpty()) unavailable("JIRA_SYNC_NOT_READY")
        val normalizedKeys=keys.map{it.trim().uppercase()}.filter(String::isNotBlank).distinct()
        if(normalizedKeys.isEmpty()||normalizedKeys.size>100) invalid("JIRA_TIME_REPAIR_KEYS_INVALID")
        if(normalizedKeys.any{!it.matches(Regex("^[A-Z][A-Z0-9]+-[0-9]+$"))}) invalid("JIRA_ISSUE_KEY_INVALID")
        val contract=importPort.activeContract()
        val jql="(${properties.jql}) AND key IN (${normalizedKeys.joinToString(","){"'$it'"}}) ORDER BY id ASC"
        val issues=enrichProjects(fetchAll(jql,contract.requestedFields,maintainLease=false))
        val fetched=issues.map{it.key}.toSet();val missing=normalizedKeys.filter{it !in fetched}
        val rows=issues.map{issueMapper.toMappedSourceRow(it,contract)}
        val now=LocalDateTime.ofInstant(clock.instant(),ZoneId.of(properties.timeZone))
        val batch=if(rows.isEmpty())null else importPort.stage(rows,contract,"BACKFILL",now.minusDays(1),now.plusDays(1),instanceHash(),actor)
        return JiraFormalReviewResult(batch!=null||missing.isNotEmpty(),batch?.batchId,fetched.sorted(),missing.sorted(),status())
    }

    fun enqueueScheduled() {
        if (readinessErrors().isNotEmpty()) { ensureState(); return }
        ensureState()
        states.enqueueScheduledIfDue(properties.connectionKey)
    }

    fun runQueued() {
        if (readinessErrors().isNotEmpty()) return
        ensureState()
        val state = states.claim(properties.connectionKey, workerId) ?: return
        try {
            val now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(properties.timeZone))
            val upper = state.requestedTo ?: now
            val lower = state.requestedFrom ?: state.watermark?.minusMinutes(properties.overlapMinutes.toLong())
                ?: upper.minusDays(properties.initialLookbackDays.toLong())
            if(lower.isAfter(upper)) unavailable("JIRA_SYNC_WINDOW_INVALID")
            val contract = importPort.activeContract()
            val issues = enrichProjects(fetchAll(buildJql(lower, upper), contract.requestedFields))
            val mappedRows = issues.map { issueMapper.toMappedSourceRow(it, contract) }
            val batchView = importPort.stage(mappedRows, contract, state.requestedTrigger ?: "SCHEDULED",
                lower, upper, instanceHash(), state.requestedBy)
            if (state.requestedTrigger == "BACKFILL") {
                issues.forEach { issue ->
                    val updated = issue.fields.path("updated").asText().takeIf(String::isNotBlank)
                        ?.let { JiraDescriptionDimensions.parseJiraDateTime(it, properties.timeZone) }
                        ?: upper
                    attachmentQueue?.enqueueBackfill(issue.key, updated)
                }
            }
            val batchId = importPort.databaseBatchId(batchView.batchId)
            states.complete(properties.connectionKey, workerId, upper, batchId, properties.scheduleIntervalMinutes)
            log.info("Jira 400 sync staged batch_id={} issue_count={} trigger={}", batchView.batchId, mappedRows.size, state.requestedTrigger)
        } catch (error: Throwable) {
            states.fail(properties.connectionKey, workerId, error)
            val remote = error as? RemoteException
            log.warn(
                "Jira 400 sync failed errorCode={} reasonCode={} retryable={}",
                remote?.errorCode?.name ?: ErrorCode.INTERNAL_ERROR.name,
                remote?.reasonCode ?: "JIRA_SYNC_INTERNAL_FAILURE",
                remote?.errorCode?.retryable ?: true
            )
        }
    }

    private fun fetchAll(jql: String, fields: List<String>, maintainLease:Boolean = true): List<JiraApiIssue> {
        val result = mutableListOf<JiraApiIssue>()
        var startAt = 0
        while (true) {
            val page = gateway.search(jql, startAt, properties.pageSize, fields)
            if(maintainLease&&!states.heartbeat(properties.connectionKey,workerId))
                throw com.feedback.common.exception.ServiceException(
                    ErrorCode.JIRA_SYNC_CONTRACT_INVALID,
                    "JIRA_SYNC_LEASE_LOST"
                )
            if (page.startAt != startAt) throw com.feedback.common.exception.ServiceException(
                ErrorCode.JIRA_SYNC_CONTRACT_INVALID,
                "JIRA_PAGINATION_OFFSET_MISMATCH"
            )
            if (result.size + page.issues.size > properties.maxIssuesPerRun || page.total > properties.maxIssuesPerRun)
                throw com.feedback.common.exception.ServiceException(
                    ErrorCode.JIRA_SYNC_CONTRACT_INVALID,
                    "JIRA_SYNC_LIMIT_EXCEEDED"
                )
            result += page.issues
            if (page.isLast) return result
            if (page.issues.isEmpty()) throw com.feedback.common.exception.ServiceException(
                ErrorCode.JIRA_SYNC_CONTRACT_INVALID,
                "JIRA_PAGINATION_NO_PROGRESS"
            )
            startAt += page.issues.size
        }
    }

    private fun enrichProjects(issues: List<JiraApiIssue>): List<JiraApiIssue> {
        val details = issues.mapNotNull { it.fields.path("project").path("key").asText().takeIf(String::isNotBlank) }
            .distinct().associateWith(gateway::project)
        return issues.map { issue ->
            val key = issue.fields.path("project").path("key").asText()
            val detail = details[key] as? ObjectNode ?: return@map issue
            val fields = issue.fields.deepCopy<ObjectNode>()
            val project = (fields.path("project") as? ObjectNode)?.deepCopy() ?: detail.objectNode()
            detail.fields().forEachRemaining { (name, value) -> if (!project.hasNonNull(name)) project.set<JsonNode>(name, value) }
            fields.set<ObjectNode>("project", project)
            issue.copy(fields = fields)
        }
    }

    private fun readinessErrors(): List<String> {
        val connectionErrors = properties.readinessErrors()
        if (connectionErrors.isNotEmpty()) return connectionErrors
        return runCatching {
            importPort.activeContract()
        }.fold(onSuccess = { emptyList() }, onFailure = { listOf(it.message ?: "JIRA_MAPPING_NOT_READY") })
    }

    private fun buildJql(from: LocalDateTime, to: LocalDateTime): String {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        return "(${properties.jql}) AND updated >= '${from.format(format)}' AND updated <= '${to.format(format)}' ORDER BY updated ASC, id ASC"
    }

    private fun ensureState() = states.ensure(properties.connectionKey, instanceHash(), properties.enabled)
    private fun instanceHash(): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest((properties.baseUrl.trimEnd('/').lowercase().ifBlank { properties.connectionKey }).toByteArray())

    private fun invalid(reasonCode:String):Nothing = throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
    private fun unavailable(reasonCode:String):Nothing = throw ServiceException(ErrorCode.JIRA_SYNC_CONTRACT_INVALID,reasonCode)
}

@Component
class Jira400SyncScheduler(private val service: Jira400SyncService) {
    fun schedule() = service.enqueueScheduled()
}

@Component
class Jira400SyncWorkerScheduler(private val service: Jira400SyncService) {
    fun runQueued() = service.runQueued()
}
