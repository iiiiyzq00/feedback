package com.feedback.service.query.detail

import com.feedback.application.port.feedback.detail.*
import com.feedback.model.auth.DataFieldPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class FeedbackTimelineAssemblerTest {
    private val assembler = FeedbackTimelineAssembler()

    @Test
    fun `assembles every historical event type with stable public wording and order`() {
        val ingest = LocalDateTime.of(2026, 8, 30, 9, 0)
        val created = ingest.plusMinutes(1)
        val aiTime = ingest.plusMinutes(2)
        val clusterTime = ingest.plusMinutes(3)
        val fieldTime = ingest.plusMinutes(4)
        val policy = DataFieldPolicy(true, emptySet(), emptySet())

        val result = assembler.assemble(
            FeedbackTimelineFacts(
                source = source("JIRA", ingest, 8),
                content = content(created),
                ai = ai(aiTime),
                importBatch = FeedbackImportBatchSnapshot(batchId = "batch-8", batchStatus = "COMPLETED"),
                importRow = FeedbackTimelineImportRowSnapshot(sheetName = "Jira", rowNumber = 12),
                clusterLinks = listOf(
                    FeedbackTimelineClusterSnapshot(
                        id = 7, clusterId = 3, createdAt = clusterTime, reviewStatus = "APPROVED",
                        decisionReason = "同一故障", decisionVersion = "decision-v1",
                        matchSimilarity = BigDecimal("0.91")
                    )
                ),
                fieldHistory = listOf(
                    FeedbackFieldHistoryResponse(
                        11, "module", "A", "B", "MANUAL_CORRECTION", "USER", 9,
                        "mapping-v2", null, "纠正模块", fieldTime, "审核员", policy
                    )
                ),
                fieldPolicy = policy
            )
        )

        assertEquals(listOf("ingest", "mapping", "mask", "ai", "cluster-7", "field-11"), result.map { it.id })
        assertEquals("批次 batch-8 · 工作表 Jira · 第 12 行", result[0].detail)
        assertEquals("规则版本 mapping-v2", result[1].detail)
        assertEquals("状态 PASS; 仅展示脱敏后结果", result[2].detail)
        assertEquals("簇 #3 · APPROVED · 相似度 0.91 · decision-v1 · 同一故障", result[4].detail)
        assertEquals("人工修正", result[5].action)
        assertEquals("module: A → B · 纠正模块", result[5].detail)
    }

    @Test
    fun `realtime source and missing optional facts keep compatibility fallbacks`() {
        val result = assembler.assemble(
            FeedbackTimelineFacts(
                source = source("SDK", LocalDateTime.of(2026, 8, 30, 9, 0), null),
                content = null,
                ai = null,
                importBatch = null,
                importRow = null,
                clusterLinks = emptyList(),
                fieldHistory = emptyList(),
                fieldPolicy = DataFieldPolicy(true, emptySet(), emptySet())
            )
        )

        assertEquals(1, result.size)
        assertEquals("实时接入", result.single().detail)
        assertEquals("primary", result.single().type)
    }

    @Test
    fun `field history values cannot bypass policy through generic detail`() {
        val policy = DataFieldPolicy(
            unrestricted = false,
            allowed = setOf("DETAIL", "ACTION", "FIELD_NAME", "NEW_VALUE"),
            denied = setOf("OLD_VALUE", "CHANGE_REASON", "CHANGE_TYPE")
        )
        val result = assembler.assemble(
            FeedbackTimelineFacts(
                source = source("SDK", LocalDateTime.of(2026, 8, 30, 9, 0), null),
                content = null,
                ai = null,
                importBatch = null,
                importRow = null,
                clusterLinks = emptyList(),
                fieldHistory = listOf(
                    FeedbackFieldHistoryResponse(
                        12, "account", "secret-before", "safe-after", "MANUAL_CORRECTION", "USER", 9,
                        null, null, "secret-reason", LocalDateTime.of(2026, 8, 30, 9, 1), "审核员", policy
                    )
                ),
                fieldPolicy = policy
            )
        )

        val fieldEvent = result.single { it.id == "field-12" }
        assertEquals("字段转换", fieldEvent.action)
        assertEquals("account: 已隐藏 → safe-after", fieldEvent.detail)
    }

    private fun source(channel: String, ingest: LocalDateTime, batchId: Long?) = FeedbackSourceSnapshot(
        id = 1, sourceChannel = channel, sourceRecordId = "record-1", appVersion = null,
        provinceCode = null, cityCode = null, districtCode = null, faultOccurredAt = null,
        faultTimePrecision = null, faultTimeSource = null, faultTimeResolution = null,
        ingestTime = ingest, sourceUpdateTime = null, importBatchId = batchId
    )

    private fun content(created: LocalDateTime) = FeedbackContentSnapshot(
        title = null, description = null, functionModule = null, usageStage = null, problemType = null,
        issueCategory = null, severityLevel = null, userSentiment = null, reproducibility = null,
        processingStatus = "OPEN", maskingStatus = "PASS", mappingRuleVersion = "mapping-v2",
        normalizationVersion = null, dataQualityScore = null, qualityRuleVersion = null,
        updatedAt = null, deleted = false, createdAt = created
    )

    private fun ai(time: LocalDateTime) = FeedbackAiSnapshot(
        aiSummary = null, aiApplication = null, aiFunctionModule = null, aiCategory = null,
        aiSentiment = null, aiRiskType = null, recommendedTeam = null, dataSources = null,
        aiSeverity = null, aiConfidence = null, aiExplanation = null, aiModelVersion = "model-v3",
        aiProcessedAt = time, classificationPromptVersion = null, reviewStatus = null
    )
}
