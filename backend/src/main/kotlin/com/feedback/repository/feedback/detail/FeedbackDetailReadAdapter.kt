package com.feedback.repository.feedback.detail

import com.feedback.application.port.feedback.detail.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.FeedbackChannel
import com.feedback.common.AttachmentSecurityStatus
import com.feedback.common.AttachmentContentReviewStatus
import com.feedback.common.AttachmentDecryptionStatus
import com.feedback.common.AttachmentIntegrityStatus
import com.feedback.common.AttachmentMalwareScanStatus
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.config.Jira400Properties
import com.feedback.model.po.feedback.detail.*
import com.feedback.repository.attachment.SecureAttachmentRepository
import com.feedback.model.auth.DataAccessScope
import org.springframework.stereotype.Repository

/**
 * MyBatis 详情事实到不可变应用快照的适配器。
 * JSON 只在此基础设施边界解析；各入口保持原有单表查询次数，不做逐行回表。
 */
@Repository
class FeedbackDetailReadRepository(
    private val repository: FeedbackDetailRepository,
    private val attachments: SecureAttachmentRepository,
    private val navigation: FeedbackNavigationResolver,
    private val objectMapper: ObjectMapper
) : FeedbackDetailReadPort, FeedbackModificationReadPort, FeedbackTimelineReadPort, FeedbackExternalLinkReadPort {

    override fun read(sourceId: Long, scope: DataAccessScope): FeedbackDetailSnapshot {
        val source = repository.source(sourceId)?.toSnapshot()
            ?: throw ClientException(ErrorCode.NOT_FOUND, "FEEDBACK_NOT_FOUND")
        val content = repository.content(sourceId)
            ?.takeIf { !it.deleted && it.maskingStatus == "PASS" }
            ?.toSnapshot()
            ?: throw ClientException(ErrorCode.NOT_FOUND, "FEEDBACK_NOT_FOUND")
        val ai = repository.ai(sourceId)?.toSnapshot()
        val importBatch = source.importBatchId?.let(repository::importBatch)?.toSnapshot()
        val workflow = repository.workflow(sourceId)?.toSnapshot()
        val assignment = repository.assignment(sourceId)?.toSnapshot()
        val history = history(sourceId)
        val users = repository.users(buildSet {
            assignment?.ownerUserId?.let(::add)
            history.mapNotNullTo(this) { it.userId }
        })
        val channel = channelFacts(source.sourceChannel, sourceId)
        val diagnostics = FeedbackDiagnosticSnapshot(
            identityTypes = repository.identityTypes(sourceId).toSet(),
            hasActiveLog = attachments.hasActiveLog(sourceId)
        )
        val attachmentSummaries = attachments.findNonLogListFacts(sourceId)
            .map { it.toSummaryFact() }
        val resolvedNavigation = navigation.resolve(source, scope)
        val cluster = repository.clusterSummary(sourceId)?.let { (link, value) -> FeedbackClusterSnapshot(
            id = value.id, clusterName = value.clusterName, totalRecordCount = value.totalRecordCount,
            matchSimilarity = link.matchSimilarity, decisionReason = link.decisionReason,
            decisionVersion = link.decisionVersion
        ) }
        return FeedbackDetailSnapshot(
            source = source,
            content = content,
            ai = ai,
            importBatch = importBatch,
            workflow = workflow,
            assignment = assignment,
            owner = assignment?.ownerUserId?.let(users::get)?.let {
                FeedbackOwnerSnapshot(displayName = it.displayName, username = it.username)
            },
            channelFacts = channel,
            externalLinkFacts = channel.toExternalFacts(source.sourceChannel),
            diagnostics = diagnostics,
            fieldHistory = history.withOperators(users),
            attachments = attachmentSummaries,
            navigation = resolvedNavigation,
            cluster = cluster
        )
    }

    override fun readModifications(sourceId: Long): List<FeedbackFieldModificationFact> {
        val history = history(sourceId)
        val users = repository.users(history.mapNotNull(FeedbackFieldModificationFact::userId).toSet())
        return history.withOperators(users)
    }

    override fun readTimeline(sourceId: Long): FeedbackTimelineSnapshot {
        val source = repository.source(sourceId)?.toSnapshot()
            ?: throw ClientException(ErrorCode.NOT_FOUND, "FEEDBACK_NOT_FOUND")
        val content = repository.content(sourceId)?.toSnapshot()
        val history = history(sourceId)
        val users = repository.users(history.mapNotNull(FeedbackFieldModificationFact::userId).toSet())
        return FeedbackTimelineSnapshot(
            source = source,
            content = content,
            ai = repository.ai(sourceId)?.toSnapshot(),
            importBatch = source.importBatchId?.let(repository::importBatch)?.toSnapshot(),
            importRow = repository.importRow(sourceId)?.let {
                FeedbackTimelineImportRowSnapshot(sheetName = it.sheetName, rowNumber = it.rowNumber)
            },
            clusterLinks = repository.timelineClusterLinks(sourceId).map { row ->
                FeedbackTimelineClusterSnapshot(
                    id = row.id,
                    clusterId = row.clusterId,
                    createdAt = row.createdAt,
                    reviewStatus = row.reviewStatus,
                    decisionReason = row.decisionReason,
                    decisionVersion = row.decisionVersion,
                    matchSimilarity = row.matchSimilarity
                )
            },
            fieldHistory = history.withOperators(users)
        )
    }

    override fun source(sourceId: Long): FeedbackExternalSourceFact = repository.source(sourceId)
        ?.let { FeedbackExternalSourceFact(channel = it.sourceChannel) }
        ?: throw ClientException(ErrorCode.NOT_FOUND, "FEEDBACK_NOT_FOUND")

    override fun channel(sourceId: Long, channel: String): FeedbackExternalLinkFacts =
        when (FeedbackChannel.fromStorage(channel)) {
            FeedbackChannel.JIRA -> JiraExternalLinkFacts(repository.jira(sourceId)?.jiraKey)
            FeedbackChannel.REDBLACK -> RedblackExternalLinkFacts(repository.redblack(sourceId)?.url)
            else -> UnsupportedExternalLinkFacts(channel)
        }

    private fun history(sourceId: Long): List<FeedbackFieldModificationFact> =
        repository.fieldHistory(sourceId).map { row ->
            val detail = row.operationDetail?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
            FeedbackFieldModificationFact(
                id = row.id,
                fieldName = detail.text("fieldName"),
                oldValue = detail.text("oldValue"),
                newValue = detail.text("newValue"),
                changeType = detail.text("changeType"),
                changedByType = detail.text("changedByType"),
                userId = row.userId,
                mappingVersion = detail.text("mappingVersion"),
                normalizationVersion = detail.text("normalizationVersion"),
                changeReason = detail.text("changeReason"),
                createdAt = row.eventTime ?: row.createdAt,
                operator = null
            )
        }

    private fun List<FeedbackFieldModificationFact>.withOperators(users: Map<Long, FeedbackDetailUserPo>) =
        map { fact ->
            val user = fact.userId?.let(users::get)
            fact.copy(operator = user?.displayName ?: user?.username)
        }

    private fun channelFacts(channel: String, sourceId: Long): FeedbackChannelFacts? =
        when (FeedbackChannel.fromStorage(channel)) {
            FeedbackChannel.SDK -> repository.sdk(sourceId)?.let { row ->
                SdkFeedbackChannelFacts(
                    clientFeedbackId = row.clientFeedbackId, appId = row.appId, packageName = row.packageName,
                    osVersion = row.osVersion, networkType = row.networkType, deviceModel = row.deviceModel,
                    screenOrientation = row.screenOrientation, inputMethods = row.inputMethods,
                    triggerType = row.triggerType, batteryLevel = row.batteryLevel,
                    storageAvailableMb = row.storageAvailableMb, integrationMode = row.integrationMode,
                    contextSource = row.contextSource, pageRoute = row.pageRoute, businessId = row.businessId,
                    crashStack = row.crashStack, courseId = row.courseId, questionId = row.questionId,
                    orderId = row.orderId, apiRequestSummary = row.apiRequestSummary,
                    stateMachine = row.stateMachine, customEvents = row.customEvents,
                    diagnosticTypes = row.diagnosticTypes
                )
            }
            FeedbackChannel.JIRA -> repository.jira(sourceId)?.let { row ->
                JiraFeedbackChannelFacts(
                    jiraKey = row.jiraKey, priorityRaw = row.priorityRaw, jiraStatusRaw = row.jiraStatusRaw,
                    projectKey = row.projectKey, projectName = row.projectName, resolution = row.resolution,
                    assignee = row.assignee, jiraCreatedAt = row.jiraCreatedAt, jiraUpdatedAt = row.jiraUpdatedAt,
                    resolvedAt = row.resolvedAt, affectedVersion = row.affectedVersion, fixVersion = row.fixVersion,
                    moduleRaw = row.moduleRaw, dueDate = row.dueDate, label1 = row.label1, label2 = row.label2,
                    label3 = row.label3, commentSummary = row.commentSummary
                )
            }
            FeedbackChannel.QUALITY -> repository.quality(sourceId)?.let { row ->
                QualityFeedbackChannelFacts(
                    returnOrderNo = row.returnOrderNo, productName = row.productName, productModel = row.productModel,
                    dispatchDate = row.dispatchDate, activationDate = row.activationDate,
                    repairRegion = row.repairRegion, province = row.province, city = row.city,
                    county = row.county, district = row.district, applicationTypeRaw = row.applicationTypeRaw,
                    exchangeRequest = row.exchangeRequest, returnReason = row.returnReason,
                    faultConfirmed = row.faultConfirmed, faultRemark = row.faultRemark,
                    merchantReturnApplyDate = row.merchantReturnApplyDate,
                    actualShippingDate = row.actualShippingDate
                )
            }
            FeedbackChannel.REDBLACK -> repository.redblack(sourceId)?.let { row ->
                RedblackFeedbackChannelFacts(
                    originalIdRaw = row.originalIdRaw, headline = row.headline, author = row.author,
                    sourcePlatform = row.sourcePlatform, pubTypeName = row.pubTypeName, listType = row.listType,
                    sectionRaw = row.sectionRaw, folderName = row.folderName, keyword = row.keyword,
                    emotionTypeRaw = row.emotionTypeRaw, evaluationObject = row.evaluationObject,
                    evaluationDimension = row.evaluationDimension, impactLevelRaw = row.impactLevelRaw,
                    dataPeriod = row.dataPeriod, sourceDepartmentMasked = row.sourceDepartmentMasked,
                    sourceRoleMasked = row.sourceRoleMasked, pushTime = row.pushTime, pubTime = row.pubTime,
                    createTime = row.createTime, updateTime = row.updateTime, sourceDeleted = row.sourceDeleted,
                    url = row.url
                )
            }
            FeedbackChannel.STATION_FEEDBACK -> repository.station(sourceId)?.let { row ->
                StationFeedbackChannelFacts(
                    sourceApiVersion = row.sourceApiVersion, productId = row.productId,
                    sourceProductName = row.sourceProductName, sourceAppId = row.sourceAppId,
                    sourceAppName = row.sourceAppName, sourcePlatform = row.sourcePlatform,
                    sourceFeedbackType = row.sourceFeedbackType, sourceSubChannel = row.sourceSubChannel,
                    sourceDescription = row.sourceDescription, attributeSchemaVersion = row.attributeSchemaVersion,
                    attributeMappingStatus = row.attributeMappingStatus, contactStatus = row.contactStatus,
                    ingestStatus = row.ingestStatus, processingStage = row.processingStage, retryCount = row.retryCount,
                    processedAt = row.processedAt, createdAt = row.createdAt,
                    sourceUserId = row.sourceUserId,
                    authenticatedUserId = row.authenticatedUserId
                )
            }
            else -> null
        }

    private fun FeedbackChannelFacts?.toExternalFacts(channel: String): FeedbackExternalLinkFacts = when (this) {
        is JiraFeedbackChannelFacts -> JiraExternalLinkFacts(jiraKey)
        is RedblackFeedbackChannelFacts -> RedblackExternalLinkFacts(url)
        else -> UnsupportedExternalLinkFacts(channel)
    }

    private fun JsonNode?.text(field: String): String? = this?.get(field)
        ?.takeUnless { it.isNull || it.isMissingNode }
        ?.asText()
}

@Repository
class FeedbackExternalLinkConfigurationRepository(
    private val repository: FeedbackDetailRepository,
    private val jiraProperties: Jira400Properties? = null
) : FeedbackExternalLinkConfigurationPort {
    override fun jiraBaseUrl(): String? = repository.activeConfigString("jira.base-url")?.takeIf(String::isNotBlank)
        ?: jiraProperties?.baseUrl?.takeIf(String::isNotBlank)
}

private fun FeedbackDetailSourcePo.toSnapshot() = FeedbackSourceSnapshot(
    id = id, sourceChannel = sourceChannel, sourceRecordId = sourceRecordId, appVersion = appVersion,
    provinceCode = provinceCode, cityCode = cityCode, districtCode = districtCode,
    faultOccurredAt = faultOccurredAt, faultTimePrecision = faultTimePrecision, faultTimeSource = faultTimeSource,
    faultTimeResolution = faultTimeResolution, ingestTime = ingestTime, sourceUpdateTime = sourceUpdateTime,
    importBatchId = importBatchId
)

private fun FeedbackDetailContentPo.toSnapshot() = FeedbackContentSnapshot(
    title = title, description = description, functionModule = functionModule, usageStage = usageStage,
    problemType = problemType, issueCategory = issueCategory, severityLevel = severityLevel,
    userSentiment = userSentiment, reproducibility = reproducibility, processingStatus = processingStatus,
    maskingStatus = maskingStatus, mappingRuleVersion = mappingRuleVersion, normalizationVersion = normalizationVersion,
    dataQualityScore = dataQualityScore, qualityRuleVersion = qualityRuleVersion, updatedAt = updatedAt,
    deleted = deleted, createdAt = createdAt
)

private fun FeedbackDetailAiPo.toSnapshot() = FeedbackAiSnapshot(
    aiSummary = aiSummary, aiApplication = aiApplication, aiFunctionModule = aiFunctionModule,
    aiCategory = aiCategory, aiSentiment = aiSentiment, aiRiskType = aiRiskType,
    recommendedTeam = recommendedTeam, dataSources = dataSources, aiSeverity = aiSeverity,
    aiConfidence = aiConfidence, aiExplanation = aiExplanation, aiModelVersion = aiModelVersion,
    aiProcessedAt = aiProcessedAt, classificationPromptVersion = classificationPromptVersion, reviewStatus = reviewStatus
)
private fun FeedbackDetailImportBatchPo.toSnapshot() = FeedbackImportBatchSnapshot(batchId = batchId, batchStatus = batchStatus)
private fun FeedbackDetailWorkflowPo.toSnapshot() = FeedbackWorkflowSnapshot(
    processingStatus = processingStatus, distributionStatus = distributionStatus,
    statusMappingVersion = statusMappingVersion, statusMappingResult = statusMappingResult, statusMappedAt = statusMappedAt
)
private fun FeedbackDetailAssignmentPo.toSnapshot() = FeedbackAssignmentSnapshot(
    ownerUserId = ownerUserId, assignmentVersion = assignmentVersion, assignedAt = assignedAt
)

private fun com.feedback.application.port.feedback.attachment.AttachmentListFacts.toSummaryFact() = FeedbackAttachmentSummaryFact(
    id = id,
    attachmentType = attachmentType?.wireValue.orEmpty(),
    mimeType = mimeType,
    fileSizeBytes = fileSizeBytes,
    width = width,
    height = height,
    durationSeconds = durationSeconds,
    uploadStatus = uploadStatus?.code,
    securityStatus = securityStatus?.wireValue,
    integrityStatus = integrityStatus?.code,
    decryptionStatus = decryptionStatus?.code,
    malwareScanStatus = malwareScanStatus?.code,
    contentReviewStatus = contentReviewStatus?.code,
    sourceKind = sourceKind?.code,
    retentionUntil = retentionUntil,
    createdAt = createdAt,
    available = deletedAt == null && storedContentPresent &&
        securityStatus == AttachmentSecurityStatus.VERIFIED &&
        integrityStatus == AttachmentIntegrityStatus.VERIFIED &&
        decryptionStatus in setOf(AttachmentDecryptionStatus.NOT_REQUIRED, AttachmentDecryptionStatus.DECRYPTED) &&
        malwareScanStatus == AttachmentMalwareScanStatus.CLEAN &&
        contentReviewStatus == AttachmentContentReviewStatus.PASS &&
        (retentionUntil == null || retentionUntil.isAfter(queryTime))
)
