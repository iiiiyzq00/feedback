package com.feedback.repository.report

import com.feedback.mapper.report.*
import com.feedback.model.query.report.ReportFactSourceQuery
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** 单表报告事实读取边界；Service 只接收 typed PO 并在内存中做当前批次关联。 */
@Repository
class SixReportFactsRepository(
    private val source: ReportFactSourceMapper,
    private val content: ReportFactContentMapper,
    private val link: ReportFactClusterLinkMapper,
    private val cluster: ReportFactClusterMapper,
    private val identity: ReportFactIdentityMapper,
    private val sdk: ReportFactSdkMapper,
    private val jira: ReportFactJiraMapper,
    private val redblack: ReportFactRedblackMapper,
    private val ai: ReportFactAiMapper,
    private val importBatch: ReportFactImportBatchMapper,
    private val importRow: ReportFactImportRowMapper,
    private val config: ReportFactConfigMapper,
    private val alertInstance: ReportFactAlertInstanceMapper,
    private val alertRule: ReportFactAlertRuleMapper,
) {
    fun sourcePage(query: ReportFactSourceQuery) = source.page(query)
    fun contents(ids: List<Long>) = content.bySourceIds(ids)
    fun links(ids: List<Long>) = link.activeBySourceIds(ids)
    fun clusters(ids: List<Long>) = cluster.byIds(ids)
    fun identities(ids: List<Long>) = identity.bySourceIds(ids)
    fun sdk(ids: List<Long>) = sdk.bySourceIds(ids)
    fun jira(ids: List<Long>) = jira.bySourceIds(ids)
    fun redblack(ids: List<Long>) = redblack.bySourceIds(ids)
    fun ai(ids: List<Long>) = ai.bySourceIds(ids)
    fun importBatchPage(from: LocalDateTime?, to: LocalDateTime?, channel: String?, afterId: Long, limit: Int = 500) =
        importBatch.pageCreated(from, to, channel, afterId, limit)
    fun importBatches(ids: List<Long>) = importBatch.byIds(ids)
    fun failedImportCounts(batchIds: List<Long>) = importRow.failedCounts(batchIds)
    fun pendingAiReviews(to: LocalDateTime) = ai.pendingReviewCount(to)
    fun latestConfig(key: String) = config.latestActive(key)?.value
    fun activeAlerts(afterId: Long, limit: Int = 500) = alertInstance.activeCriticalPage(afterId, limit)
    fun alertRules(ids: List<Long>) = alertRule.byIds(ids)
}
