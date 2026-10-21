package com.feedback.service.cluster

import com.feedback.service.ai.PromptAssetBootstrap
import com.feedback.service.normalization.FaultOccurrenceTimeBackfillRunner
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * 受控重放 shadow-2 原 57 条 UNKNOWN_FAILURE_MODE，并导出 v3 语义保真自动分类结果。该工具只允许写入 /private/tmp，
 * 不输出原始正文到构建日志，也不修改应用身份、Cluster、MAIN 或 generation。
 */
@EnabledIfEnvironmentVariable(named = "CLUSTER_UNKNOWN_EXPORT_ENABLED", matches = "true")
@SpringBootTest(properties = [
    "app.cluster.classification-v2-shadow-enabled=true",
    "app.cluster.reclassification-worker-enabled=false",
    "app.jira.assignee-owner-backfill-enabled=false",
    "xxl.job.enabled=false",
    "spring.flyway.enabled=false",
    "spring.datasource.hikari.read-only=true",
    "spring.main.lazy-initialization=true"
])
class ClusterUnknownFailureModeExportTest @Autowired constructor(
    private val input: HistoricalClusterShadowInputPort,
    private val objectMapper: ObjectMapper
) {
    @MockkBean(relaxed = true)
    private lateinit var faultOccurrenceBackfill: FaultOccurrenceTimeBackfillRunner

    @MockkBean(relaxed = true)
    private lateinit var promptAssetBootstrap: PromptAssetBootstrap

    @Test
    fun `导出首批未知故障模式的二次脱敏标注输入`() {
        val output = exportPath()
        val extractor = AtomicProblemExtractorV3()
        val selected = input.read(afterSourceId = 0, limit = 100).records
            .filter { it.classificationInput.sourceId in ORIGINAL_UNKNOWN_SOURCE_IDS }
        val assignments = ClusterShadowClassifierV3().classify(selected.map { it.classificationInput })
            .assignments.associateBy(ShadowClusterAssignment::sourceId)
        val records = selected
            .map { record ->
                val item = record.classificationInput
                val profile = extractor.extract(item)
                val assignment = assignments.getValue(item.sourceId)
                UnknownFailureExportRow(
                    sourceId = item.sourceId,
                    application = sanitize(item.applicationName.orEmpty(), 80),
                    functionModule = sanitize(item.functionModule.orEmpty(), 120),
                    issueCategory = sanitize(item.issueCategory.orEmpty(), 120),
                    problemText = sanitize(
                        listOfNotNull(item.title, item.description).filter(String::isNotBlank).joinToString("。"),
                        600
                    ),
                    classificationStatus = profile.status.name,
                    failureMode = profile.failureMode,
                    target = profile.target,
                    canonicalSymptom = profile.canonicalSymptom,
                    classificationReason = profile.reason,
                    evidenceSource = profile.evidenceSource?.name,
                    clusterDecision = assignment.decision,
                    proposedClusterName = assignment.clusterName
                )
            }

        assertEquals(ORIGINAL_UNKNOWN_SOURCE_IDS, records.map(UnknownFailureExportRow::sourceId).toSet(),
            "ORIGINAL_UNKNOWN_FAILURE_MODE_SAMPLE_CHANGED")
        Files.createDirectories(output.parent)
        Files.newBufferedWriter(output).use { writer ->
            records.forEach { row ->
                writer.append(objectMapper.writeValueAsString(row)).appendLine()
            }
        }
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"))
        println("CLUSTER_UNKNOWN_EXPORT rows=${records.size} output=${output.fileName}")
    }

    private fun exportPath(): Path {
        val configured = System.getenv("CLUSTER_UNKNOWN_EXPORT_PATH")
            ?: error("CLUSTER_UNKNOWN_EXPORT_PATH_REQUIRED")
        val path = Path.of(configured).toAbsolutePath().normalize()
        require(path.parent == Path.of("/private/tmp")) { "CLUSTER_UNKNOWN_EXPORT_PATH_NOT_ALLOWED" }
        require(path.fileName.toString() == "cluster-unknown-shadow-2.jsonl") {
            "CLUSTER_UNKNOWN_EXPORT_FILENAME_NOT_ALLOWED"
        }
        return path
    }

    private fun sanitize(value: String, maxLength: Int): String {
        var sanitized = value
            .replace(
                Regex("(?i)(\\\"?(?:sessionId|taskId|fileKey)\\\"?\\s*[:=：]\\s*\\\"?)[a-z0-9._:/-]{8,}"),
                "$1[TECH_ID]"
            )
            .replace(Regex("(?i)https?://\\S+|www\\.\\S+"), "[URL]")
            .replace(Regex("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}"), "[EMAIL]")
            .replace(Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "[PHONE]")
            .replace(Regex("(?<![0-9Xx])\\d{17}[0-9Xx](?![0-9Xx])"), "[ID_CARD]")
            .replace(Regex("(?<!\\d)(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?!\\d)"), "[IP]")
            .replace(Regex("(?i)((?:儿童姓名|孩子姓名|学生姓名|姓名|联系人)\\s*[:：]?\\s*)[\\p{IsHan}]{2,4}"), "$1[CHILD_OR_CONTACT_NAME]")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (sanitized.length > maxLength) sanitized = sanitized.take(maxLength) + "…"
        return sanitized
    }

    private data class UnknownFailureExportRow(
        val sourceId: Long,
        val application: String,
        val functionModule: String,
        val issueCategory: String,
        val problemText: String,
        val classificationStatus: String,
        val failureMode: String?,
        val target: String?,
        val canonicalSymptom: String?,
        val classificationReason: String,
        val evidenceSource: String?,
        val clusterDecision: String,
        val proposedClusterName: String?
    )

    companion object {
        private val ORIGINAL_UNKNOWN_SOURCE_IDS = setOf(
            7066L, 7067L, 7068L, 7071L, 7073L, 7074L, 7077L, 7080L, 7081L, 7082L,
            7083L, 7085L, 7086L, 7088L, 7089L, 7090L, 7091L, 7092L, 7093L, 7094L,
            7097L, 7100L, 7102L, 7104L, 7107L, 7109L, 7110L, 7111L, 7112L, 7114L,
            7115L, 7116L, 7118L, 7119L, 7125L, 7129L, 7132L, 7134L, 7137L, 7138L,
            7140L, 7145L, 7146L, 7147L, 7148L, 7149L, 7150L, 7152L, 7153L, 7156L,
            7157L, 7158L, 7159L, 7160L, 7161L, 7162L, 7164L
        )
    }
}
