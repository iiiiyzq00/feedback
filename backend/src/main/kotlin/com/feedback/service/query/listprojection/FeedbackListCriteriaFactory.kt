package com.feedback.service.query.listprojection

import com.feedback.application.port.feedback.list.FeedbackListCriteria
import com.feedback.common.FeedbackChannel
import com.feedback.common.ErrorCode
import com.feedback.common.WorkflowStatus
import com.feedback.common.exception.ClientException
import com.feedback.model.auth.DataAccessScope
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import com.feedback.service.query.FeedbackListQuery
import org.springframework.stereotype.Component

data class FeedbackListAccess(
    val criteria: FeedbackListCriteria,
    val fieldPolicy: DataFieldPolicy,
    val actorId: Long
)

/** 将应用查询归一化并与当前数据权限求交；不读取投影、不决定 ACTIVE generation。 */
@Component
class FeedbackListCriteriaFactory(private val dataPermissions: DataPermissionService) {
    private val feedbackChannels = FeedbackChannel.feedbackWireValues

    fun validate(query: FeedbackListQuery) {
        val page = query.page
        val common = query.common
        if (page.page < 1) invalid("PAGE_INVALID")
        if (page.size !in 1..100) invalid("SIZE_INVALID")
        if (query.sort.sortBy !in SORT_FIELDS) invalid("FEEDBACK_SORT_INVALID")
        if (query.sort.direction.lowercase() !in setOf("asc", "desc")) invalid("FEEDBACK_SORT_DIRECTION_INVALID")
        if (common.startTime != null && common.endTime != null && common.startTime.isAfter(common.endTime)) {
            invalid("FEEDBACK_TIME_RANGE_INVALID")
        }
    }

    fun create(query: FeedbackListQuery, principal: WebPrincipal): FeedbackListAccess {
        val permissionSnapshot = dataPermissions.snapshot()
        val scope = permissionSnapshot.scope
        val allowedChannels = scope.channels.intersect(feedbackChannels)
        return FeedbackListAccess(criteria(query, scope, allowedChannels, principal.userId), permissionSnapshot.fieldPolicy, principal.userId)
    }

    private fun criteria(
        query: FeedbackListQuery,
        scope: DataAccessScope,
        allowedChannels: Set<String>,
        currentUserId: Long
    ): FeedbackListCriteria {
        val common = query.common
        val application = query.application
        val jira = query.jira
        val quality = query.quality
        val redblack = query.redblack
        val station = query.station
        val singleChannel = common.channel?.takeIf(String::isNotBlank)?.uppercase()?.also {
            if (it !in feedbackChannels) invalid("FEEDBACK_CHANNEL_INVALID")
        }
        val channelList = common.channels.filter(String::isNotBlank).map(String::uppercase).distinct().also { values ->
            if (!values.all { it in feedbackChannels }) invalid("FEEDBACK_CHANNEL_INVALID")
        }
        val requested = when {
            singleChannel != null && channelList.isNotEmpty() -> channelList.filter { it == singleChannel }
            singleChannel != null -> listOf(singleChannel)
            channelList.isNotEmpty() -> channelList
            else -> null
        }
        val scopeChannels = allowedChannels.toList().sorted().ifEmpty { listOf("__NONE__") }
        val requestedWithinScope = requested?.intersect(allowedChannels)?.toList()?.sorted()?.ifEmpty { listOf("__NONE__") }
        if (listOf(common.ownerId != null, common.mine, common.unassigned).count { it } > 1) {
            invalid("FEEDBACK_OWNER_FILTER_CONFLICT")
        }
        common.maskingStatus?.takeIf(String::isNotBlank)?.let {
            if (!it.equals("PASS", true)) invalid("MASKING_STATUS_RESTRICTED_TO_PRIVACY_WORKBENCH")
        }
        val resolutionState = jira.resolutionState.boundedOrNull(200)?.uppercase()?.also {
            if (it !in setOf("ALL", "UNRESOLVED", "RESOLVED")) invalid("JIRA_RESOLUTION_STATE_INVALID")
        }?.takeUnless { it == "ALL" }
        val versionCompleteness = application.versionCompleteness.boundedOrNull(200)?.uppercase()?.also {
            if (it !in setOf("MISSING", "COMPLETE")) invalid("VERSION_COMPLETENESS_INVALID")
        }
        val aiReview = common.aiReviewStatus?.takeIf(String::isNotBlank)?.uppercase()?.also {
            if (it !in setOf("PENDING", "NOT_REQUIRED", "CONFIRMED", "CORRECTED", "SKIPPED")) {
                invalid("AI_REVIEW_STATUS_INVALID")
            }
        }
        return FeedbackListCriteria(
            allowedChannels = scopeChannels,
            scopeModules = scope.modules?.normalizedScope(), scopeRegions = scope.regions?.normalizedScope(),
            channels = requestedWithinScope,
            keyword = common.keyword?.trim()?.takeIf(String::isNotBlank)?.bounded(200),
            severities = (common.severities + listOfNotNull(common.severity)).normalizedUpperList(),
            statuses = (common.statuses + listOfNotNull(common.status)).map(::workflowStatus).normalizedUpperList(),
            modules = (common.modules + listOfNotNull(common.module)).normalizedList(),
            problemTypes = common.problemTypes.normalizedUpperList(), reproducibilities = common.reproducibilities.normalizedUpperList(),
            aiReviewStatus = aiReview, province = common.province.boundedOrNull(100), city = common.city.boundedOrNull(100),
            ownerId = common.ownerId ?: currentUserId.takeIf { common.mine }, unassigned = common.unassigned,
            handoffPending = common.handoffPending, startTime = common.startTime, endTime = common.endTime,
            appId = application.appId.boundedOrNull(200), packageName = application.packageName.boundedOrNull(200),
            deviceModel = application.deviceModel.boundedOrNull(200),
            osVersion = application.osVersion.boundedOrNull(200), networkType = application.networkType.boundedOrNull(200),
            appVersion = application.appVersion.boundedOrNull(200), projectKey = jira.projectKey.boundedOrNull(200),
            issueKey = jira.issueKey.boundedOrNull(200), issueType = jira.issueType.boundedOrNull(200),
            priority = jira.priority.boundedOrNull(200), assignee = jira.assignee.boundedOrNull(200), resolution = jira.resolution.boundedOrNull(200),
            repairRegion = quality.repairRegion.boundedOrNull(200), applicationType = quality.applicationType.boundedOrNull(200),
            exchangeRequest = quality.exchangeRequest.boundedOrNull(200)?.let(::parseBoolean),
            importBatchCode = common.batchId.boundedOrNull(200), sourcePlatform = common.sourcePlatform.boundedOrNull(200),
            emotionType = redblack.emotionType.boundedOrNull(200), channelKeyword = redblack.channelKeyword.boundedOrNull(200),
            listType = redblack.listType.boundedOrNull(200), productId = station.productId.boundedOrNull(200),
            sourceSubChannel = station.sourceSubChannel.boundedOrNull(200), apiVersion = station.apiVersion.boundedOrNull(200),
            versionCompleteness = versionCompleteness, sourceResolutionState = resolutionState,
            sortBy = query.sort.sortBy, sortDirection = query.sort.direction.lowercase()
        )
    }

    private fun String.bounded(max: Int): String = also { if (length > max) invalid("FEEDBACK_FILTER_TOO_LONG") }
    private fun String?.boundedOrNull(max: Int): String? = this?.trim()?.takeIf(String::isNotBlank)?.bounded(max)
    private fun Collection<String>.normalizedList(): List<String>? = map(String::trim).filter(String::isNotBlank)
        .onEach { it.bounded(200) }.distinct().takeIf(List<String>::isNotEmpty)
    private fun Collection<String>.normalizedUpperList(): List<String>? = map(String::uppercase).normalizedList()
    private fun Set<String>.normalizedScope(): List<String> = map(String::uppercase).sorted().ifEmpty { listOf("__NONE__") }
    private fun workflowStatus(value: String): String = WorkflowStatus.fromBoundary(value)?.wireValue
        ?: invalid("FEEDBACK_STATUS_INVALID")
    private fun parseBoolean(value: String): Boolean = when (value.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> invalid("FEEDBACK_BOOLEAN_FILTER_INVALID")
    }

    private fun invalid(reasonCode: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)

    private companion object {
        val SORT_FIELDS = setOf("reportedAt", "sourceUpdatedAt", "platformUpdatedAt", "severity", "status", "title")
    }
}
