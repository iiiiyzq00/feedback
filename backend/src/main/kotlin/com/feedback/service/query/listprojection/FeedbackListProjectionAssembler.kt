package com.feedback.service.query.listprojection

import com.feedback.common.DiagnosticType
import com.feedback.common.FeedbackChannel
import com.feedback.common.LogicalChannel
import com.feedback.common.WorkflowStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.feedback.model.po.feedback.listprojection.*
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionAuxiliaryFacts
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListProjectionSnapshot(
    val projection: FeedbackListProjectionPo,
    val clusterMember: FeedbackClusterMemberProjectionPo?,
    val clusterIdentities: List<FeedbackClusterIdentityProjectionPo>
)

private sealed interface FeedbackListSafeChannelMetadata

private data class FeedbackListSafeSdkChannelMetadata(
    val clientFeedbackId: String?, val appId: String?, val packageName: String?, val osVersion: String?,
    val networkType: String?, val deviceModel: String?, val screenOrientation: String?, val inputMethods: String?,
    val triggerType: String?, val batteryLevel: BigDecimal?, val storageAvailableMb: Long?,
    val integrationMode: String?, val contextSource: String?, val pageRoute: String?, val businessId: String?,
    val courseId: String?, val questionId: String?, val orderId: String?
) : FeedbackListSafeChannelMetadata

private data class FeedbackListSafeJiraChannelMetadata(
    val jiraKey: String?, val priorityRaw: String?, val jiraStatusRaw: String?, val projectKey: String?,
    val projectName: String?, val resolution: String?, val assignee: String?, val jiraCreatedAt: LocalDateTime?,
    val jiraUpdatedAt: LocalDateTime?, val resolvedAt: LocalDateTime?, val affectedVersion: String?,
    val fixVersion: String?, val moduleRaw: String?, val dueDate: LocalDate?,
    @get:com.fasterxml.jackson.annotation.JsonProperty("label_1") val label1: String?,
    @get:com.fasterxml.jackson.annotation.JsonProperty("label_2") val label2: String?,
    @get:com.fasterxml.jackson.annotation.JsonProperty("label_3") val label3: String?
) : FeedbackListSafeChannelMetadata

private data class FeedbackListSafeQualityChannelMetadata(
    val returnOrderNo: String?, val productName: String?, val productModel: String?, val dispatchDate: LocalDate?,
    val activationDate: LocalDate?, val repairRegion: String?, val province: String?, val city: String?,
    val county: String?, val district: String?, val applicationTypeRaw: String?, val exchangeRequest: Boolean?,
    val merchantReturnApplyDate: LocalDateTime?, val actualShippingDate: LocalDate?
) : FeedbackListSafeChannelMetadata

private data class FeedbackListSafeRedblackChannelMetadata(
    val originalIdRaw: String?, val author: String?, val sourcePlatform: String?, val pubTypeName: String?,
    val listType: String?, val sectionRaw: String?, val folderName: String?, val keyword: String?,
    val emotionTypeRaw: String?, val evaluationObject: String?, val evaluationDimension: String?,
    val impactLevelRaw: String?, val dataPeriod: String?, val sourceDepartmentMasked: String?,
    val sourceRoleMasked: String?, val pushTime: LocalDateTime?, val pubTime: LocalDateTime?,
    val createTime: LocalDateTime?, val updateTime: LocalDateTime?,
    @get:com.fasterxml.jackson.annotation.JsonProperty("is_deleted_src") val sourceDeleted: Boolean?
) : FeedbackListSafeChannelMetadata

private data class FeedbackListSafeStationChannelMetadata(
    val sourceApiVersion: String?, val productId: String?, val sourceProductName: String?, val sourceAppId: String?,
    val sourceAppName: String?, val sourcePlatform: String?, val sourceFeedbackType: String?,
    val sourceSubChannel: String?, val attributeSchemaVersion: String?, val attributeMappingStatus: String?,
    val contactStatus: String?, val ingestStatus: String?, val processingStage: String?, val retryCount: Int?,
    val processedAt: LocalDateTime?, val createdAt: LocalDateTime?
) : FeedbackListSafeChannelMetadata

/**
 * 将已经通过安全资格判断的单表事实组装为列表投影。
 *
 * 本组件不读取数据库、不判断用户权限，也不产生公开响应。渠道 JSON 只序列化具名且经过
 * 白名单选择的 facts 类型；这些类型刻意不含外链、联系方式、密文、附件路径或诊断原文。
 */
@Component
class FeedbackListProjectionAssembler(private val objectMapper: ObjectMapper) {
    private val feedbackChannels = FeedbackChannel.feedbackWireValues
    private val channelMetaMapper = objectMapper.copy()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    fun isSafelyEligible(source: FeedbackListSourceFactsPo?, content: FeedbackListContentFactsPo?): Boolean =
        source != null && content != null &&
            source.sourceChannel in feedbackChannels &&
            source.dataEnvironment == "PRODUCTION" &&
            content.maskingStatus == "PASS" &&
            !content.deleted

    fun assemble(
        generationId: Long,
        source: FeedbackListSourceFactsPo,
        content: FeedbackListContentFactsPo,
        auxiliary: FeedbackListProjectionAuxiliaryFacts
    ): FeedbackListProjectionSnapshot {
        if (!isSafelyEligible(source, content)) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_UNSAFE_FACTS")
        val sourceId = source.id
        val ai = auxiliary.aiBySourceId[sourceId]
        val workflow = auxiliary.workflowBySourceId[sourceId]
        val assignment = auxiliary.assignmentBySourceId[sourceId]
        val handoff = auxiliary.handoffBySourceId[sourceId]
        val attachment = auxiliary.attachmentBySourceId[sourceId]
        val channel = auxiliary.channelBySourceId[sourceId]
        val logicalChannel = logicalChannel(source.sourceChannel)
        val importBatch = source.importBatchId?.let(auxiliary.importBatchById::get)
        val jiraBatchCodes = auxiliary.importRowsBySourceId[sourceId].orEmpty()
            .mapNotNull { auxiliary.importBatchById[it.batchId]?.batchId }.distinct().sorted()
        val processingStatus = workflow?.processingStatus
            ?: WorkflowStatus.fromBoundary(content.processingStatus)?.wireValue
            ?: WorkflowStatus.NEW.wireValue
        val distributionStatus = workflow?.distributionStatus ?: if (assignment == null) "UNASSIGNED" else "ACCEPTED"
        val diagnosticTypes = buildSet {
            auxiliary.identityTypesBySourceId[sourceId].orEmpty().mapTo(this) { it.identityType }
            if ((attachment?.logCount ?: 0) > 0) add("APPLICATION_LOG")
            when (channel) {
                is FeedbackListSdkFactsPo -> channel.diagnosticTypes.orEmpty().split(',')
                    .filter(String::isNotBlank).mapTo(this, ::normalizeDiagnosticType)
                is FeedbackListStationFactsPo -> {
                    if (channel.hasSourceUserId) add("USER_ID")
                    if (channel.hasAuthenticatedUserId) add("AUTHENTICATED_USER_ID")
                }
                else -> Unit
            }
        }.sorted()
        val projection = FeedbackListProjectionPo(
            generationId = generationId,
            sourceId = sourceId,
            sourceChannel = source.sourceChannel,
            sourceRecordId = source.sourceRecordId,
            appVersion = source.appVersion,
            packageName = source.packageName,
            provinceCode = source.provinceCode,
            cityCode = source.cityCode,
            importBatchId = source.importBatchId,
            importBatchCode = importBatch?.batchId,
            jiraImportBatchCodes = jiraBatchCodes.takeIf(List<String>::isNotEmpty)?.let(objectMapper::writeValueAsString),
            title = content.title,
            description = content.description,
            functionModule = content.functionModule,
            problemType = content.problemType,
            issueCategory = content.issueCategory,
            severityLevel = content.severityLevel,
            aiSeverity = ai?.aiSeverity,
            aiReviewStatus = ai?.reviewStatus,
            userSentiment = content.userSentiment,
            reproducibility = content.reproducibility,
            processingStatus = processingStatus,
            distributionStatus = distributionStatus,
            ownerUserId = assignment?.ownerUserId,
            handoffPending = handoff?.pending == true,
            faultOccurredAt = source.faultOccurredAt,
            sourceEventTime = source.sourceEventTime,
            reportedAt = source.faultOccurredAt ?: source.sourceEventTime,
            faultTimePrecision = source.faultTimePrecision,
            faultTimeSource = source.faultTimeSource,
            faultTimeResolution = source.faultTimeResolution,
            sourceUpdateTime = source.sourceUpdateTime,
            platformUpdateTime = content.updatedAt,
            ingestTime = source.ingestTime,
            attachmentCount = attachment?.attachmentCount ?: 0,
            screenshotCount = attachment?.screenshotCount ?: 0,
            logCount = attachment?.logCount ?: 0,
            videoCount = attachment?.videoCount ?: 0,
            channelMetaJson = safeChannelMetadata(channel)?.let(channelMetaMapper::writeValueAsString),
            diagnosticTypes = objectMapper.writeValueAsString(diagnosticTypes)
        )
        applyChannelColumns(projection, channel)

        val link = auxiliary.clusterLinkBySourceId[sourceId]
        val applicationName = applicationName(source, content, channel, auxiliary.applicationDictionary)
        val regionValue = regionValue(source, channel)
        val decision = auxiliary.clusterDecisionBySourceId[sourceId]?.let(::decisionFacts)
        val member = link?.let {
            FeedbackClusterMemberProjectionPo(
                generationId = generationId,
                sourceId = sourceId,
                clusterId = it.clusterId,
                sourceChannel = source.sourceChannel,
                logicalChannel = logicalChannel,
                clusterStatus = auxiliary.clusterById[it.clusterId]?.status
                    ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_CLUSTER_FACTS_MISSING"),
                reportedAt = projection.reportedAt,
                functionModule = content.functionModule,
                provinceCode = source.provinceCode,
                cityCode = source.cityCode,
                applicationName = applicationName,
                regionValue = regionValue,
                attachmentCount = projection.attachmentCount,
                screenshotCount = projection.screenshotCount,
                logCount = projection.logCount,
                videoCount = projection.videoCount,
                decisionType = it.linkType,
                matchSimilarity = it.matchSimilarity,
                decisionReason = safeExplanation(it.decisionReason),
                aiModel = decision?.model,
                aiConfidence = decision?.confidence,
                aiAction = decision?.action,
                aiExplanation = decision?.explanation
            )
        }
        val identities = if (link == null) emptyList() else auxiliary.identityFingerprintsBySourceId[sourceId].orEmpty().map { identity ->
            FeedbackClusterIdentityProjectionPo(
                generationId = generationId,
                sourceId = sourceId,
                clusterId = link.clusterId,
                sourceChannel = source.sourceChannel,
                logicalChannel = logicalChannel,
                functionModule = content.functionModule,
                provinceCode = source.provinceCode,
                cityCode = source.cityCode,
                regionValue = regionValue,
                reportedAt = projection.reportedAt,
                identityScope = identity.identityScope,
                identityType = identity.identityType,
                fingerprintKeyId = identity.fingerprintKeyId,
                fingerprint = identity.fingerprint
            )
        }
        return FeedbackListProjectionSnapshot(projection, member, identities)
    }

    private fun logicalChannel(sourceChannel: String): String =
        LogicalChannel.fromPhysicalStorage(sourceChannel)?.wireValue
            ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_CHANNEL_INVALID")

    private fun applicationName(
        source: FeedbackListSourceFactsPo,
        content: FeedbackListContentFactsPo,
        channel: FeedbackListChannelFactsPo?,
        dictionary: List<String>
    ): String? = when (channel) {
        is FeedbackListSdkFactsPo -> channel.appId.nonBlank() ?: source.packageName.nonBlank() ?: channel.packageName.nonBlank()
        is FeedbackListStationFactsPo -> channel.sourceAppName.nonBlank() ?: channel.sourceProductName.nonBlank()
        is FeedbackListJiraFactsPo -> dictionary.asSequence()
            .filter { name -> content.title.orEmpty().contains(name) || content.description.orEmpty().contains(name) }
            .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
            .firstOrNull()
        is FeedbackListQualityFactsPo -> channel.productName.nonBlank()
        else -> null
    }?.take(200)

    private fun regionValue(source: FeedbackListSourceFactsPo, channel: FeedbackListChannelFactsPo?): String? =
        when (channel) {
            is FeedbackListQualityFactsPo -> channel.province.nonBlank() ?: source.provinceCode.nonBlank()
                ?: channel.repairRegion.nonBlank()
            else -> source.provinceCode.nonBlank()
        }?.take(100)

    private fun decisionFacts(row: FeedbackListClusterDecisionFactsPo): SafeDecisionFacts {
        val json = row.outputSnapshot?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
        return SafeDecisionFacts(
            model = row.modelNameSnapshot.nonBlank()?.take(100),
            confidence = json?.path("confidence")?.takeIf { it.isNumber }?.decimalValue()
                ?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.ONE },
            action = json?.path("recommendedAction")?.takeIf { it.isTextual }?.textValue().nonBlank()?.take(30),
            explanation = safeExplanation(json?.path("explanation")?.takeIf { it.isTextual }?.textValue())
        )
    }

    private fun safeExplanation(value: String?): String? = value.nonBlank()
        ?.filterNot(Char::isISOControl)
        ?.take(500)
        ?.takeIf(String::isNotBlank)

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotBlank)

    private data class SafeDecisionFacts(
        val model: String?, val confidence: BigDecimal?, val action: String?, val explanation: String?
    )

    private fun applyChannelColumns(row: FeedbackListProjectionPo, channel: FeedbackListChannelFactsPo?) {
        when (channel) {
            is FeedbackListSdkFactsPo -> {
                row.channelAppId = channel.appId
                row.channelPackageName = channel.packageName
                row.channelDeviceModel = channel.deviceModel
                row.channelOsVersion = channel.osVersion
                row.channelNetworkType = channel.networkType
            }
            is FeedbackListJiraFactsPo -> {
                row.jiraKey = channel.jiraKey
                row.jiraIssueType = channel.issueTypeRaw
                row.jiraStatus = channel.jiraStatusRaw
                row.jiraPriority = channel.priorityRaw
                row.jiraProjectKey = channel.projectKey
                row.jiraAssignee = channel.assignee
                row.jiraResolution = channel.resolution
                row.jiraResolvedAt = channel.resolvedAt
            }
            is FeedbackListQualityFactsPo -> {
                row.qualityRepairRegion = channel.repairRegion
                row.qualityApplicationType = channel.applicationTypeRaw
                row.qualityExchangeRequest = channel.exchangeRequest
            }
            is FeedbackListRedblackFactsPo -> {
                row.redblackSourcePlatform = channel.sourcePlatform
                row.redblackKeyword = channel.keyword
                row.redblackListType = channel.listType
            }
            is FeedbackListStationFactsPo -> {
                row.stationProductId = channel.productId
                row.stationAppId = channel.sourceAppId
                row.stationSourcePlatform = channel.sourcePlatform
                row.stationSourceSubChannel = channel.sourceSubChannel
                row.stationApiVersion = channel.sourceApiVersion
            }
            null -> Unit
        }
    }

    private fun safeChannelMetadata(channel: FeedbackListChannelFactsPo?): FeedbackListSafeChannelMetadata? = when (channel) {
        is FeedbackListSdkFactsPo -> FeedbackListSafeSdkChannelMetadata(
            channel.clientFeedbackId, channel.appId, channel.packageName, channel.osVersion, channel.networkType,
            channel.deviceModel, channel.screenOrientation, channel.inputMethods, channel.triggerType,
            channel.batteryLevel, channel.storageAvailableMb, channel.integrationMode, channel.contextSource,
            channel.pageRoute, channel.businessId, channel.courseId, channel.questionId, channel.orderId
        )
        is FeedbackListJiraFactsPo -> FeedbackListSafeJiraChannelMetadata(
            channel.jiraKey, channel.priorityRaw, channel.jiraStatusRaw, channel.projectKey, channel.projectName,
            channel.resolution, channel.assignee, channel.jiraCreatedAt, channel.jiraUpdatedAt, channel.resolvedAt,
            channel.affectedVersion, channel.fixVersion, channel.moduleRaw, channel.dueDate,
            channel.label1, channel.label2, channel.label3
        )
        is FeedbackListQualityFactsPo -> FeedbackListSafeQualityChannelMetadata(
            channel.returnOrderNo, channel.productName, channel.productModel, channel.dispatchDate,
            channel.activationDate, channel.repairRegion, channel.province, channel.city, channel.county,
            channel.district, channel.applicationTypeRaw, channel.exchangeRequest,
            channel.merchantReturnApplyDate, channel.actualShippingDate
        )
        is FeedbackListRedblackFactsPo -> FeedbackListSafeRedblackChannelMetadata(
            channel.originalIdRaw, channel.author, channel.sourcePlatform, channel.pubTypeName, channel.listType,
            channel.sectionRaw, channel.folderName, channel.keyword, channel.emotionTypeRaw, channel.evaluationObject,
            channel.evaluationDimension, channel.impactLevelRaw, channel.dataPeriod, channel.sourceDepartmentMasked,
            channel.sourceRoleMasked, channel.pushTime, channel.pubTime, channel.createTime, channel.updateTime,
            channel.sourceDeleted
        )
        is FeedbackListStationFactsPo -> FeedbackListSafeStationChannelMetadata(
            channel.sourceApiVersion, channel.productId, channel.sourceProductName, channel.sourceAppId,
            channel.sourceAppName, channel.sourcePlatform, channel.sourceFeedbackType, channel.sourceSubChannel,
            channel.attributeSchemaVersion, channel.attributeMappingStatus, channel.contactStatus,
            channel.ingestStatus, channel.processingStage, channel.retryCount, channel.processedAt, channel.createdAt
        )
        null -> null
    }

    private fun normalizeDiagnosticType(value: String): String = DiagnosticType.normalizeWire(value)
}
