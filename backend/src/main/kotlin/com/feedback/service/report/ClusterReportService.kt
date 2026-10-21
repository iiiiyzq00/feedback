package com.feedback.service.report

import com.feedback.model.po.report.ClusterReportCauseLinkPo
import com.feedback.model.po.report.ClusterReportContentPo
import com.feedback.model.po.report.ClusterReportSourcePo
import com.feedback.repository.report.ClusterReportRepository
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.service.cluster.ProductPhenomenonFormatter
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** 问题簇详情及 RCA/知识/Incident 关联查询；所有数据范围过滤在批量单表事实装配后执行。 */
@Service
class ClusterReportService(
    private val repository: ClusterReportRepository,
    private val permissions: DataPermissionService,
) {
    private fun detail(id: Long, startTime: LocalDateTime? = null, endTime: LocalDateTime? = null): Map<String, Any?> {
        if((startTime == null) != (endTime == null)) reportInvalid("CLUSTER_TIME_RANGE_INCOMPLETE")
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) reportInvalid("CLUSTER_TIME_RANGE_INVALID")
        val scope = validatedScope()
        val cluster = repository.cluster(id) ?: reportNotFound("CLUSTER_NOT_FOUND")
        val linkedIds = repository.sourceLinks(id).map { it.sourceId }.distinct()
        val sourceById = repository.sources(linkedIds).associateBy { it.id }
        val contentBySource = repository.contents(linkedIds).groupBy { it.sourceId }
        val eligible = linkedIds.mapNotNull { sourceId ->
            val source = sourceById[sourceId] ?: return@mapNotNull null
            val contents = contentBySource[sourceId].orEmpty()
            val content = contents.firstOrNull { permitted(scope, source, it) } ?: return@mapNotNull null
            val time = source.occurredAt
            if (startTime != null && endTime != null && (time == null || time < startTime || time > endTime)) return@mapNotNull null
            EligibleSource(source, content)
        }
        if (eligible.isEmpty()) reportNotFound("CLUSTER_NOT_FOUND")

        val eligibleIds = eligible.map { it.source.id }
        val identityRows = repository.identities(eligibleIds)
        val userKeys = identityRows.filter { it.scope == "PERSON" }.map(::identityKey).toSet()
        val deviceKeys = identityRows.filter { it.type == "DEVICE_SN" }.map(::identityKey).toSet()
        val first = eligible.mapNotNull { it.source.occurredAt }.minOrNull()
        val latest = eligible.mapNotNull { it.source.occurredAt }.maxOrNull()
        val representativeSource = eligible.sortedWith(
            compareByDescending<EligibleSource> { it.source.occurredAt }.thenByDescending { it.source.id },
        ).first()
        // 原查询只读取时间最新的一条来源；最新正文为空时不能越过它改选更旧样本。
        val representative = representativeSource.content.description?.takeIf(String::isNotBlank)
            ?: representativeSource.content.title?.takeIf(String::isNotBlank)
        val counts = eligible.groupingBy { logicalChannel(it.source.channel) }.eachCount()

        return linkedMapOf(
            "id" to cluster.id,
            "name" to cluster.name,
            "canonicalSymptom" to ProductPhenomenonFormatter.choose(
                cluster.canonicalSymptom,
                representative,
                cluster.functionModule,
                cluster.issueCategory,
            ),
            "functionModule" to cluster.functionModule,
            "issueCategory" to cluster.issueCategory,
            "status" to cluster.status,
            "lifecycleVersion" to cluster.lifecycleVersion,
            "mergedIntoClusterId" to cluster.mergedIntoClusterId,
            "splitFromClusterId" to cluster.splitFromClusterId,
            "statusReason" to cluster.statusReason,
            "totalFeedback" to eligibleIds.distinct().size.toLong(),
            "channelCount" to counts.size.toLong(),
            "latestTime" to latest,
            "firstTime" to first,
            "stationCount" to (counts["LEARNING_MACHINE"] ?: 0).toLong(),
            "jiraCount" to (counts["JIRA"] ?: 0).toLong(),
            "qualityCount" to (counts["QUALITY"] ?: 0).toLong(),
            "redblackCount" to (counts["REDBLACK"] ?: 0).toLong(),
            "uniqueUsers" to userKeys.size.takeIf { it > 0 }?.toLong(),
            "uniqueDevices" to deviceKeys.size.toLong(),
            "activeDays" to if (first == null || latest == null) 0L else
                ChronoUnit.DAYS.between(first.toLocalDate(), latest.toLocalDate()) + 1,
        )
    }

    fun rca(id: Long): List<Map<String, Any?>> {
        ensureVisible(id)
        return rcaRows(repository.causeLinks(id))
    }

    fun knowledge(id: Long): List<Map<String, Any?>> {
        ensureVisible(id)
        return knowledgeRows(repository.causeLinks(id))
    }

    fun incidents(id: Long): List<Map<String, Any?>> {
        ensureVisible(id)
        val ids = repository.causeLinks(id).mapNotNull { it.incidentId }.distinct()
        return repository.incidents(ids)
            .sortedWith(compareByDescending<com.feedback.model.po.report.ClusterReportIncidentPo> { it.triggeredAt }.thenByDescending { it.id })
            .map { row ->
                linkedMapOf(
                    "id" to row.id,
                    "level" to row.level,
                    "title" to row.title,
                    "status" to row.status,
                    "triggeredAt" to row.triggeredAt,
                )
            }
    }

    fun feedbackRcaKnowledge(sourceId: Long): Map<String, Any?> {
        val link = repository.mainLinksForSource(sourceId).firstOrNull()
            ?: return emptyFeedbackKnowledge()
        val visible = runCatching { detail(link.clusterId) }.getOrElse {
            if (it is NoSuchElementException) return emptyFeedbackKnowledge()
            throw it
        }
        val causeLinks = repository.causeLinks(link.clusterId)
        return mapOf(
            "cluster" to mapOf("id" to link.clusterId, "name" to visible["name"]),
            "rca" to rcaRows(causeLinks),
            "knowledge" to knowledgeRows(causeLinks),
        )
    }

    private fun rcaRows(links: List<ClusterReportCauseLinkPo>): List<Map<String, Any?>> =
        repository.rcas(links.mapNotNull { it.rcaId }.distinct())
            .sortedWith(compareByDescending<com.feedback.model.po.report.ClusterReportRcaPo> { it.createdAt }.thenByDescending { it.id })
            .map { row ->
                linkedMapOf(
                    "id" to row.id,
                    "incidentId" to row.incidentId,
                    "title" to row.title,
                    "rcaType" to row.rcaType,
                    "triggerType" to row.triggerType,
                    "rootCause" to row.rootCause,
                    "solution" to row.solution,
                    "status" to row.status,
                    "confidence" to row.confidence,
                    "createdAt" to row.createdAt,
                )
            }

    private fun knowledgeRows(links: List<ClusterReportCauseLinkPo>): List<Map<String, Any?>> =
        repository.knowledge(links.mapNotNull { it.rcaId }.distinct())
            .sortedWith(compareByDescending<com.feedback.model.po.report.ClusterReportKnowledgePo> { it.createdAt }.thenByDescending { it.id })
            .map { row ->
                linkedMapOf(
                    "id" to row.id,
                    "title" to row.title,
                    "content" to row.content,
                    "source" to row.source,
                    "status" to row.status,
                    "adoptionCount" to row.adoptionCount,
                    "createdAt" to row.createdAt,
                )
            }

    private fun ensureVisible(id: Long) {
        detail(id)
    }

    private fun validatedScope(): DataAccessScope = permissions.scope().also { scope ->
        if (scope.channels.isEmpty() || scope.modules?.isEmpty() == true || scope.regions?.isEmpty() == true) {
            reportForbidden("CLUSTER_SCOPE_FORBIDDEN")
        }
    }

    private fun permitted(scope: DataAccessScope, source: ClusterReportSourcePo, content: ClusterReportContentPo): Boolean =
        source.channel.uppercase() in scope.channels &&
            (scope.modules == null || (content.functionModule ?: "UNKNOWN").uppercase() in scope.modules) &&
            (scope.regions == null || setOfNotNull(source.province, source.city).map(String::uppercase).any { it in scope.regions })

    private fun logicalChannel(channel: String) = if (channel in setOf("SDK", "STATION_FEEDBACK")) "LEARNING_MACHINE" else channel
    private fun identityKey(row: com.feedback.model.po.report.ClusterReportIdentityPo) =
        "${row.keyId}:${row.fingerprint.joinToString("") { byte -> "%02X".format(byte) }}"
    private fun emptyFeedbackKnowledge() = mapOf("cluster" to null, "rca" to emptyList<Any>(), "knowledge" to emptyList<Any>())
    private data class EligibleSource(val source: ClusterReportSourcePo, val content: ClusterReportContentPo)
}
