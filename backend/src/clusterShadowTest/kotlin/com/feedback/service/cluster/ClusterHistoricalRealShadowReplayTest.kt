package com.feedback.service.cluster

import com.feedback.service.ai.PromptAssetBootstrap
import com.feedback.service.normalization.FaultOccurrenceTimeBackfillRunner
import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 真实历史回放只在显式开启环境变量时运行。测试禁用调度和 Flyway，并把连接池设为只读；
 * 输出仅包含聚合评测数据，不在构建日志暴露历史反馈正文。
 */
@EnabledIfEnvironmentVariable(named = "CLUSTER_REAL_SHADOW_TEST_ENABLED", matches = "true")
@SpringBootTest(properties = [
    "app.cluster.classification-v2-shadow-enabled=true",
    "app.cluster.reclassification-worker-enabled=false",
    "app.jira.assignee-owner-backfill-enabled=false",
    "xxl.job.enabled=false",
    "spring.flyway.enabled=false",
    "spring.datasource.hikari.read-only=true",
    "spring.main.lazy-initialization=true"
])
class ClusterHistoricalRealShadowReplayTest @Autowired constructor(
    private val service: HistoricalClusterShadowService
) {
    /** 启动期回填与提示词发布都不属于只读影子评测，真实回放中显式隔离。 */
    @MockkBean(relaxed = true)
    private lateinit var faultOccurrenceBackfill: FaultOccurrenceTimeBackfillRunner

    @MockkBean(relaxed = true)
    private lateinit var promptAssetBootstrap: PromptAssetBootstrap

    @Test
    fun `一百条真实历史只读回放可重复且保持有界`() {
        val first = service.evaluate(afterSourceId = 0, limit = 100)
        val second = service.evaluate(afterSourceId = 0, limit = 100)

        assertEquals(first, second)
        assertTrue(first.processedCount in 0..100)
        assertTrue(first.assignments.size <= 100)
        val statusCounts = first.assignments.groupingBy { it.status }.eachCount()
            .toSortedMap(compareBy(ProblemClassificationStatus::name))
        val actionable = first.assignments.filter { it.status == ProblemClassificationStatus.ACTIONABLE }
        val actionablePairs = pairwiseChanges(actionable)
        println(
            "CLUSTER_REAL_SHADOW algorithm=${first.algorithmVersion} processed=${first.processedCount} " +
                "currentClusters=${first.currentClusterCount} proposedClusters=${first.proposedClusterCount} " +
                "terminal=${first.nonActionableCount} changedPairs=${first.pairwiseChangedCount} " +
                "mergedPairs=${first.pairwiseMergedCount} splitPairs=${first.pairwiseSplitCount} " +
                "manualLocked=${first.assignments.count(HistoricalShadowAssignment::manualLocked)} " +
                "actionableCurrentClusters=${actionable.mapNotNull { it.currentClusterId }.toSet().size} " +
                "actionableProposedClusters=${actionable.mapNotNull { it.proposedClusterKey }.toSet().size} " +
                "actionableMergedPairs=${actionablePairs.first} actionableSplitPairs=${actionablePairs.second} " +
                "statusCounts=$statusCounts nextSourceId=${first.nextSourceId} mayHaveMore=${first.mayHaveMore}"
        )
        // 构建日志只保留聚合计数；sourceId、clusterId、正文和业务名称均不得写入测试报告。
    }

    @Test
    fun `五百条真实历史分批只读回放可重复且跨批关系稳定`() {
        val first = replayBatches(5)
        val second = replayBatches(5)

        assertEquals(first, second)
        assertTrue(first.size in 1..5)
        val assignments = first.flatMap(HistoricalClusterShadowReport::assignments)
        assertTrue(assignments.size in 1..500)
        assertEquals(assignments.size, assignments.map(HistoricalShadowAssignment::sourceId).distinct().size)
        val pairwise = pairwiseChanges(assignments)
        val statusCounts = assignments.groupingBy(HistoricalShadowAssignment::status).eachCount()
            .toSortedMap(compareBy(ProblemClassificationStatus::name))
        println(
            "CLUSTER_REAL_SHADOW_EXPANDED algorithm=${first.map(HistoricalClusterShadowReport::algorithmVersion).distinct()} " +
                "batches=${first.size} processed=${assignments.size} " +
                "currentClusters=${assignments.mapNotNull(HistoricalShadowAssignment::currentClusterId).toSet().size} " +
                "proposedClusters=${assignments.mapNotNull(HistoricalShadowAssignment::proposedClusterKey).toSet().size} " +
                "terminal=${assignments.count { it.status != ProblemClassificationStatus.ACTIONABLE }} " +
                "mergedPairs=${pairwise.first} splitPairs=${pairwise.second} statusCounts=$statusCounts " +
                "nextSourceId=${first.last().nextSourceId} mayHaveMore=${first.last().mayHaveMore}"
        )
    }

    private fun replayBatches(maxBatches: Int): List<HistoricalClusterShadowReport> {
        val reports = mutableListOf<HistoricalClusterShadowReport>()
        var cursor = 0L
        repeat(maxBatches) {
            val report = service.evaluate(afterSourceId = cursor, limit = 100)
            reports += report
            if (report.processedCount == 0 || !report.mayHaveMore) return reports
            assertTrue(report.nextSourceId > cursor, "CLUSTER_REAL_SHADOW_CURSOR_NOT_ADVANCING")
            cursor = report.nextSourceId
        }
        return reports
    }

    private fun pairwiseChanges(assignments: List<HistoricalShadowAssignment>): Pair<Int, Int> {
        var merged = 0
        var split = 0
        for (leftIndex in assignments.indices) for (rightIndex in leftIndex + 1 until assignments.size) {
            val left = assignments[leftIndex]
            val right = assignments[rightIndex]
            val currentSame = left.currentClusterId != null && left.currentClusterId == right.currentClusterId
            val proposedSame = left.proposedClusterKey != null && left.proposedClusterKey == right.proposedClusterKey
            if (!currentSame && proposedSame) merged++
            if (currentSame && !proposedSame) split++
        }
        return merged to split
    }
}
