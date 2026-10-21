package com.feedback.service.query.detail

import com.feedback.application.port.feedback.detail.*
import com.feedback.model.auth.DataFieldPolicy
import org.springframework.stereotype.Component

/** 纯应用层详情组装器；只消费已授权的不可变应用快照，不访问数据库或当前身份。 */
@Component
class FeedbackDetailAssembler {
    fun detail(
        snapshot: FeedbackDetailSnapshot,
        externalLinkAvailable: Boolean,
        policy: DataFieldPolicy
    ): FeedbackDetailResponse {
        val source = snapshot.source
        val content = snapshot.content
        val ai = snapshot.ai
        val workflow = snapshot.workflow
        val assignment = snapshot.assignment
        val workflowStatus = workflow?.processingStatus ?: when (content.processingStatus) {
            "IN_PROGRESS" -> "PROCESSING"
            "RESOLVED" -> "RESOLVED"
            "CLOSED" -> "CLOSED"
            else -> "NEW"
        }
        val distributionStatus = workflow?.distributionStatus
            ?: if (assignment == null) "UNASSIGNED" else "ACCEPTED"
        val ownerName = snapshot.owner?.displayName?.takeIf(String::isNotEmpty) ?: snapshot.owner?.username
        val aiResult = ai?.takeIf { it.aiSummary != null }?.let { row ->
            FeedbackAiAnalysisResponse(
                summary = row.aiSummary,
                application = row.aiApplication,
                functionModule = row.aiFunctionModule,
                category = row.aiCategory,
                sentiment = row.aiSentiment,
                riskType = row.aiRiskType,
                recommendedTeam = row.recommendedTeam,
                dataSources = row.dataSources,
                severity = row.aiSeverity,
                confidence = row.aiConfidence,
                reasoning = row.aiExplanation,
                modelVersion = row.aiModelVersion,
                processedAt = row.aiProcessedAt,
                promptVersion = row.classificationPromptVersion,
                fieldPolicy = policy
            )
        }
        val cluster = snapshot.cluster?.let { value ->
            FeedbackClusterSummaryResponse(
                clusterId = value.id,
                clusterName = value.clusterName,
                total = value.totalRecordCount,
                similarity = value.matchSimilarity,
                decisionReason = value.decisionReason,
                decisionVersion = value.decisionVersion,
                fieldPolicy = policy
            )
        }
        return FeedbackDetailResponse(
            id = source.id,
            sourceChannel = source.sourceChannel.lowercase(),
            sourceRecordId = source.sourceRecordId,
            appVersion = source.appVersion,
            reportedAt = source.faultOccurredAt,
            faultTimePrecision = source.faultTimePrecision,
            faultTimeSource = source.faultTimeSource,
            faultTimeResolution = source.faultTimeResolution,
            ingestTime = source.ingestTime,
            sourceUpdatedAt = source.sourceUpdateTime,
            importBatchId = source.importBatchId,
            title = content.title?.takeIf(String::isNotEmpty)
                ?: content.description?.takeIf(String::isNotEmpty)
                ?: "反馈 #${source.id}",
            description = content.description,
            module = content.functionModule?.takeIf(String::isNotEmpty) ?: "UNKNOWN",
            usageStage = content.usageStage,
            problemType = content.problemType,
            issueCategory = content.issueCategory,
            severity = content.severityLevel ?: ai?.aiSeverity,
            canonicalSeverity = content.severityLevel,
            severityProvisional = content.severityLevel == null && ai?.aiSeverity != null,
            severityReviewStatus = ai?.reviewStatus,
            emotion = content.userSentiment,
            reproducibility = content.reproducibility,
            status = workflowStatus,
            distributionStatus = distributionStatus,
            maskingStatus = content.maskingStatus,
            platformUpdatedAt = content.updatedAt,
            statusMappingVersion = workflow?.statusMappingVersion,
            statusMappingResult = workflow?.statusMappingResult,
            statusMappedAt = workflow?.statusMappedAt,
            mappingRule = content.mappingRuleVersion,
            normalizationVersion = content.normalizationVersion,
            qualityScore = content.dataQualityScore,
            qualityRuleVersion = content.qualityRuleVersion,
            importBatchCode = snapshot.importBatch?.batchId,
            importBatchStatus = snapshot.importBatch?.batchStatus,
            ownerId = assignment?.ownerUserId,
            ownerName = ownerName,
            assignmentVersion = assignment?.assignmentVersion,
            assignedAt = assignment?.assignedAt,
            region = listOfNotNull(source.provinceCode, source.cityCode, source.districtCode).joinToString("/"),
            deviceInfo = source.appVersion,
            externalLinkAvailable = externalLinkAvailable,
            channelMeta = channelMeta(snapshot.channelFacts, policy),
            diagnosticAvailability = diagnosticAvailability(snapshot, policy),
            fieldHistory = filterHistory(snapshot.fieldHistory, policy),
            attachments = snapshot.attachments.map { fact ->
                com.feedback.dto.attachment.FeedbackAttachmentSummaryResponse(
                    id = fact.id,
                    attachmentType = fact.attachmentType,
                    mimeType = fact.mimeType,
                    fileSizeBytes = fact.fileSizeBytes,
                    width = fact.width,
                    height = fact.height,
                    durationSeconds = fact.durationSeconds,
                    uploadStatus = fact.uploadStatus,
                    securityStatus = fact.securityStatus,
                    integrityStatus = fact.integrityStatus,
                    decryptionStatus = fact.decryptionStatus,
                    malwareScanStatus = fact.malwareScanStatus,
                    contentReviewStatus = fact.contentReviewStatus,
                    sourceKind = fact.sourceKind,
                    retentionUntil = fact.retentionUntil,
                    createdAt = fact.createdAt,
                    available = fact.available,
                    fieldPolicy = policy
                )
            },
            navigation = FeedbackNavigationResponse(
                previousId = snapshot.navigation.previousId,
                nextId = snapshot.navigation.nextId,
                fieldPolicy = policy
            ),
            clusterSummary = cluster,
            aiResult = aiResult,
            fieldPolicy = policy
        )
    }

    private fun channelMeta(facts: FeedbackChannelFacts?, policy: DataFieldPolicy): FeedbackChannelMetaResponse? =
        when (facts) {
            is SdkFeedbackChannelFacts -> SdkFeedbackChannelMetaResponse(
                clientFeedbackId = facts.clientFeedbackId, appId = facts.appId, packageName = facts.packageName,
                osVersion = facts.osVersion, networkType = facts.networkType, deviceModel = facts.deviceModel,
                screenOrientation = facts.screenOrientation, inputMethods = facts.inputMethods,
                triggerType = facts.triggerType, batteryLevel = facts.batteryLevel,
                storageAvailableMb = facts.storageAvailableMb, integrationMode = facts.integrationMode,
                contextSource = facts.contextSource, pageRoute = facts.pageRoute, businessId = facts.businessId,
                crashStack = facts.crashStack, courseId = facts.courseId, questionId = facts.questionId,
                orderId = facts.orderId, apiRequestSummary = facts.apiRequestSummary,
                stateMachine = facts.stateMachine, customEvents = facts.customEvents, fieldPolicy = policy
            )
            is JiraFeedbackChannelFacts -> JiraFeedbackChannelMetaResponse(
                jiraKey = facts.jiraKey, priorityRaw = facts.priorityRaw, jiraStatusRaw = facts.jiraStatusRaw,
                projectKey = facts.projectKey, projectName = facts.projectName, resolution = facts.resolution,
                assignee = facts.assignee, jiraCreatedAt = facts.jiraCreatedAt, jiraUpdatedAt = facts.jiraUpdatedAt,
                resolvedAt = facts.resolvedAt, affectedVersion = facts.affectedVersion, fixVersion = facts.fixVersion,
                moduleRaw = facts.moduleRaw, dueDate = facts.dueDate, label1 = facts.label1, label2 = facts.label2,
                label3 = facts.label3, commentSummary = facts.commentSummary, fieldPolicy = policy
            )
            is QualityFeedbackChannelFacts -> QualityFeedbackChannelMetaResponse(
                returnOrderNo = facts.returnOrderNo, productName = facts.productName, productModel = facts.productModel,
                dispatchDate = facts.dispatchDate, activationDate = facts.activationDate,
                repairRegion = facts.repairRegion, province = facts.province, city = facts.city,
                county = facts.county, district = facts.district, applicationTypeRaw = facts.applicationTypeRaw,
                exchangeRequest = facts.exchangeRequest, returnReason = facts.returnReason,
                faultConfirmed = facts.faultConfirmed, faultRemark = facts.faultRemark,
                merchantReturnApplyDate = facts.merchantReturnApplyDate,
                actualShippingDate = facts.actualShippingDate, fieldPolicy = policy
            )
            is RedblackFeedbackChannelFacts -> RedblackFeedbackChannelMetaResponse(
                originalIdRaw = facts.originalIdRaw, headline = facts.headline, author = facts.author,
                sourcePlatform = facts.sourcePlatform, pubTypeName = facts.pubTypeName, listType = facts.listType,
                sectionRaw = facts.sectionRaw, folderName = facts.folderName, keyword = facts.keyword,
                emotionTypeRaw = facts.emotionTypeRaw, evaluationObject = facts.evaluationObject,
                evaluationDimension = facts.evaluationDimension, impactLevelRaw = facts.impactLevelRaw,
                dataPeriod = facts.dataPeriod, sourceDepartmentMasked = facts.sourceDepartmentMasked,
                sourceRoleMasked = facts.sourceRoleMasked, pushTime = facts.pushTime, pubTime = facts.pubTime,
                createTime = facts.createTime, updateTime = facts.updateTime,
                sourceDeleted = facts.sourceDeleted, fieldPolicy = policy
            )
            is StationFeedbackChannelFacts -> StationFeedbackChannelMetaResponse(
                sourceApiVersion = facts.sourceApiVersion, productId = facts.productId,
                sourceProductName = facts.sourceProductName, sourceAppId = facts.sourceAppId,
                sourceAppName = facts.sourceAppName, sourcePlatform = facts.sourcePlatform,
                sourceFeedbackType = facts.sourceFeedbackType, sourceSubChannel = facts.sourceSubChannel,
                sourceDescription = facts.sourceDescription, attributeSchemaVersion = facts.attributeSchemaVersion,
                attributeMappingStatus = facts.attributeMappingStatus, contactStatus = facts.contactStatus,
                ingestStatus = facts.ingestStatus, processingStage = facts.processingStage,
                retryCount = facts.retryCount, processedAt = facts.processedAt, createdAt = facts.createdAt,
                fieldPolicy = policy
            )
            null -> null
        }

    private fun diagnosticAvailability(
        snapshot: FeedbackDetailSnapshot,
        policy: DataFieldPolicy
    ): FeedbackDiagnosticAvailabilityResponse {
        val types = snapshot.diagnostics.identityTypes.toMutableSet()
        when (val facts = snapshot.channelFacts) {
            is StationFeedbackChannelFacts -> {
                if (!facts.sourceUserId.isNullOrBlank()) types += "USER_ID"
                if (!facts.authenticatedUserId.isNullOrBlank()) types += "AUTHENTICATED_USER_ID"
            }
            is SdkFeedbackChannelFacts -> facts.diagnosticTypes
                ?.split(',')
                ?.asSequence()
                ?.filter(String::isNotBlank)
                ?.map(com.feedback.common.DiagnosticType::normalizeWire)
                ?.forEach(types::add)
            else -> Unit
        }
        if (snapshot.diagnostics.hasActiveLog) types += "APPLICATION_LOG"
        val values = types.sorted()
        return FeedbackDiagnosticAvailabilityResponse(
            available = values.isNotEmpty(),
            types = values,
            requiresPurpose = false,
            fieldPolicy = policy
        )
    }
}
