package com.feedback.application.port.feedback.detail

import com.feedback.model.auth.DataAccessScope
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 详情读取的应用端口。实现必须只在访问守卫成功后调用，并在一个只读事务内返回不可变快照。
 * 端口不暴露 MyBatis Mapper、Criteria 或可变持久化对象。
 */
fun interface FeedbackDetailReadPort {
    fun read(sourceId: Long, scope: DataAccessScope): FeedbackDetailSnapshot
}

/** 字段历史读取端口；JSON 持久化格式必须在适配器内解析为具名事实。 */
fun interface FeedbackModificationReadPort {
    fun readModifications(sourceId: Long): List<FeedbackFieldModificationFact>
}

/** 时间轴读取端口；实现保持各单表一次读取和数据库既有稳定顺序。 */
fun interface FeedbackTimelineReadPort {
    fun readTimeline(sourceId: Long): FeedbackTimelineSnapshot
}

/** 外链所需最小来源事实读取端口，不返回通用 JSON 或持久化对象。 */
interface FeedbackExternalLinkReadPort {
    fun source(sourceId: Long): FeedbackExternalSourceFact
    fun channel(sourceId: Long, channel: String): FeedbackExternalLinkFacts
}

/** Jira 外部配置边界；动态配置优先、部署配置回退的语义由适配器封装。 */
fun interface FeedbackExternalLinkConfigurationPort {
    fun jiraBaseUrl(): String?
}

/** 导航候选读取端口；候选 keyset 和正文资格分批读取，禁止逐条回表。 */
interface FeedbackNavigationReadPort {
    fun previousCandidates(
        cursor: FeedbackNavigationCursorSnapshot,
        channels: Set<String>,
        regions: Set<String>?,
        limit: Int
    ): List<FeedbackNavigationCursorSnapshot>

    fun nextCandidates(
        cursor: FeedbackNavigationCursorSnapshot,
        channels: Set<String>,
        regions: Set<String>?,
        limit: Int
    ): List<FeedbackNavigationCursorSnapshot>

    fun content(sourceIds: List<Long>): Map<Long, FeedbackNavigationContentSnapshot>
}

/** 查看审计写端口；实现必须在独立写事务中完成，失败时抛出并阻止响应返回。 */
fun interface FeedbackDetailAuditPort {
    fun append(event: FeedbackDetailAuditEvent)
}

sealed interface FeedbackDetailAuditEvent {
    val userId: Long
    val sourceId: Long

    data class DetailViewed(
        override val userId: Long,
        override val sourceId: Long,
        val channel: String
    ) : FeedbackDetailAuditEvent

    data class ExternalLinkViewed(
        override val userId: Long,
        override val sourceId: Long,
        val channel: String,
        val host: String
    ) : FeedbackDetailAuditEvent
}

data class FeedbackSourceSnapshot(
    val id: Long,
    val sourceChannel: String,
    val sourceRecordId: String,
    val appVersion: String?,
    val provinceCode: String?,
    val cityCode: String?,
    val districtCode: String?,
    val faultOccurredAt: LocalDateTime?,
    val faultTimePrecision: String?,
    val faultTimeSource: String?,
    val faultTimeResolution: String?,
    val ingestTime: LocalDateTime?,
    val sourceUpdateTime: LocalDateTime?,
    val importBatchId: Long?
)

data class FeedbackContentSnapshot(
    val title: String?, val description: String?, val functionModule: String?, val usageStage: String?,
    val problemType: String?, val issueCategory: String?, val severityLevel: String?, val userSentiment: String?,
    val reproducibility: String?, val processingStatus: String, val maskingStatus: String,
    val mappingRuleVersion: String?, val normalizationVersion: String?, val dataQualityScore: BigDecimal?,
    val qualityRuleVersion: String?, val updatedAt: LocalDateTime?, val deleted: Boolean,
    val createdAt: LocalDateTime?
)

data class FeedbackAiSnapshot(
    val aiSummary: String?, val aiApplication: String?, val aiFunctionModule: String?, val aiCategory: String?,
    val aiSentiment: String?, val aiRiskType: String?, val recommendedTeam: String?, val dataSources: String?,
    val aiSeverity: String?, val aiConfidence: BigDecimal?, val aiExplanation: String?, val aiModelVersion: String?,
    val aiProcessedAt: LocalDateTime?, val classificationPromptVersion: String?, val reviewStatus: String?
)

data class FeedbackImportBatchSnapshot(val batchId: String?, val batchStatus: String?)
data class FeedbackWorkflowSnapshot(
    val processingStatus: String?, val distributionStatus: String?, val statusMappingVersion: String?,
    val statusMappingResult: String?, val statusMappedAt: LocalDateTime?
)
data class FeedbackAssignmentSnapshot(val ownerUserId: Long?, val assignmentVersion: Long?, val assignedAt: LocalDateTime?)
data class FeedbackOwnerSnapshot(val displayName: String?, val username: String)
data class FeedbackClusterSnapshot(
    val id: Long, val clusterName: String?, val totalRecordCount: Long?, val matchSimilarity: BigDecimal?,
    val decisionReason: String?, val decisionVersion: String?
)

/** 五类渠道详情的封闭联合；不携带 HTTP Response 或可变持久化行。 */
sealed interface FeedbackChannelFacts {
    val channel: String
}
data class SdkFeedbackChannelFacts(
    val clientFeedbackId: String?, val appId: String?, val packageName: String?, val osVersion: String?,
    val networkType: String?, val deviceModel: String?, val screenOrientation: String?, val inputMethods: String?,
    val triggerType: String?, val batteryLevel: BigDecimal?, val storageAvailableMb: Long?,
    val integrationMode: String?, val contextSource: String?, val pageRoute: String?, val businessId: String?,
    val crashStack: String?, val courseId: String?, val questionId: String?, val orderId: String?,
    val apiRequestSummary: String?, val stateMachine: String?, val customEvents: String?,
    val diagnosticTypes: String?
) : FeedbackChannelFacts { override val channel = "SDK" }
data class JiraFeedbackChannelFacts(
    val jiraKey: String?, val priorityRaw: String?, val jiraStatusRaw: String?, val projectKey: String?,
    val projectName: String?, val resolution: String?, val assignee: String?, val jiraCreatedAt: LocalDateTime?,
    val jiraUpdatedAt: LocalDateTime?, val resolvedAt: LocalDateTime?, val affectedVersion: String?,
    val fixVersion: String?, val moduleRaw: String?, val dueDate: LocalDate?, val label1: String?,
    val label2: String?, val label3: String?, val commentSummary: String?
) : FeedbackChannelFacts { override val channel = "JIRA" }
data class QualityFeedbackChannelFacts(
    val returnOrderNo: String?, val productName: String?, val productModel: String?, val dispatchDate: LocalDate?,
    val activationDate: LocalDate?, val repairRegion: String?, val province: String?, val city: String?,
    val county: String?, val district: String?, val applicationTypeRaw: String?, val exchangeRequest: Boolean?,
    val returnReason: String?, val faultConfirmed: String?, val faultRemark: String?,
    val merchantReturnApplyDate: LocalDateTime?, val actualShippingDate: LocalDate?
) : FeedbackChannelFacts { override val channel = "QUALITY" }
data class RedblackFeedbackChannelFacts(
    val originalIdRaw: String?, val headline: String?, val author: String?, val sourcePlatform: String?,
    val pubTypeName: String?, val listType: String?, val sectionRaw: String?, val folderName: String?,
    val keyword: String?, val emotionTypeRaw: String?, val evaluationObject: String?,
    val evaluationDimension: String?, val impactLevelRaw: String?, val dataPeriod: String?,
    val sourceDepartmentMasked: String?, val sourceRoleMasked: String?, val pushTime: LocalDateTime?,
    val pubTime: LocalDateTime?, val createTime: LocalDateTime?, val updateTime: LocalDateTime?,
    val sourceDeleted: Boolean?,
    val url: String?
) : FeedbackChannelFacts { override val channel = "REDBLACK" }
data class StationFeedbackChannelFacts(
    val sourceApiVersion: String?, val productId: String?, val sourceProductName: String?, val sourceAppId: String?,
    val sourceAppName: String?, val sourcePlatform: String?, val sourceFeedbackType: String?,
    val sourceSubChannel: String?, val sourceDescription: String?, val attributeSchemaVersion: String?,
    val attributeMappingStatus: String?, val contactStatus: String?, val ingestStatus: String?,
    val processingStage: String?, val retryCount: Int?, val processedAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val sourceUserId: String?,
    val authenticatedUserId: String?
) : FeedbackChannelFacts { override val channel = "STATION_FEEDBACK" }

data class FeedbackDiagnosticSnapshot(val identityTypes: Set<String>, val hasActiveLog: Boolean)

data class FeedbackDetailSnapshot(
    val source: FeedbackSourceSnapshot,
    val content: FeedbackContentSnapshot,
    val ai: FeedbackAiSnapshot?,
    val importBatch: FeedbackImportBatchSnapshot?,
    val workflow: FeedbackWorkflowSnapshot?,
    val assignment: FeedbackAssignmentSnapshot?,
    val owner: FeedbackOwnerSnapshot?,
    val channelFacts: FeedbackChannelFacts?,
    val externalLinkFacts: FeedbackExternalLinkFacts,
    val diagnostics: FeedbackDiagnosticSnapshot,
    val fieldHistory: List<FeedbackFieldModificationFact>,
    val attachments: List<FeedbackAttachmentSummaryFact>,
    val navigation: FeedbackNavigationSnapshot,
    val cluster: FeedbackClusterSnapshot?
)

data class FeedbackFieldModificationFact(
    val id: Long,
    val fieldName: String?,
    val oldValue: String?,
    val newValue: String?,
    val changeType: String?,
    val changedByType: String?,
    val userId: Long?,
    val mappingVersion: String?,
    val normalizationVersion: String?,
    val changeReason: String?,
    val createdAt: LocalDateTime?,
    val operator: String?
)

data class FeedbackTimelineImportRowSnapshot(val sheetName: String?, val rowNumber: Int?)
data class FeedbackNavigationCursorSnapshot(
    val id: Long,
    val sourceChannel: String,
    val provinceCode: String?,
    val cityCode: String?,
    val faultOccurredAt: LocalDateTime?
)
data class FeedbackNavigationContentSnapshot(
    val sourceId: Long,
    val functionModule: String?,
    val maskingStatus: String,
    val deleted: Boolean
)
data class FeedbackNavigationSnapshot(val previousId: Long?, val nextId: Long?)

/** 详情页附件摘要事实；不包含存储位置、正文、令牌或 HTTP 展示策略。 */
data class FeedbackAttachmentSummaryFact(
    val id: Long,
    val attachmentType: String,
    val mimeType: String?,
    val fileSizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val durationSeconds: Int?,
    val uploadStatus: String?,
    val securityStatus: String?,
    val integrityStatus: String?,
    val decryptionStatus: String?,
    val malwareScanStatus: String?,
    val contentReviewStatus: String?,
    val sourceKind: String?,
    val retentionUntil: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val available: Boolean
)
data class FeedbackTimelineClusterSnapshot(
    val id: Long, val clusterId: Long, val createdAt: LocalDateTime?, val reviewStatus: String?,
    val decisionReason: String?, val decisionVersion: String?, val matchSimilarity: BigDecimal?
)
data class FeedbackTimelineSnapshot(
    val source: FeedbackSourceSnapshot,
    val content: FeedbackContentSnapshot?,
    val ai: FeedbackAiSnapshot?,
    val importBatch: FeedbackImportBatchSnapshot?,
    val importRow: FeedbackTimelineImportRowSnapshot?,
    val clusterLinks: List<FeedbackTimelineClusterSnapshot>,
    val fieldHistory: List<FeedbackFieldModificationFact>
)

sealed interface FeedbackExternalLinkFacts { val channel: String }
data class FeedbackExternalSourceFact(val channel: String)
data class JiraExternalLinkFacts(val jiraKey: String?) : FeedbackExternalLinkFacts { override val channel = "JIRA" }
data class RedblackExternalLinkFacts(val url: String?) : FeedbackExternalLinkFacts { override val channel = "REDBLACK" }
data class UnsupportedExternalLinkFacts(override val channel: String) : FeedbackExternalLinkFacts
