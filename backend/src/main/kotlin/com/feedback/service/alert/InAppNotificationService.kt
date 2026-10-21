package com.feedback.service.alert

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.InAppAlertNotificationPo
import com.feedback.model.po.alert.InAppDispositionPo
import com.feedback.model.po.alert.InAppHandoffPo
import com.feedback.model.po.alert.InAppProductEventPo
import com.feedback.model.po.alert.InAppReminderPo
import com.feedback.repository.alert.InAppNotificationRepository
import com.feedback.repository.alert.InAppReadRow
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Schema(description = "站内通知统一记录；仅返回当前认证用户可见的业务事实")
data class InAppNotificationItem(
    @field:Schema(description = "通知 ID；非告警类使用兼容负数命名空间", example = "123")
    val id: Long,
    @field:Schema(description = "关联告警实例 ID；非告警通知为空", example = "20")
    val alertInstanceId: Long? = null,
    @field:Schema(description = "关联报告 ID；无报告时为空", example = "31")
    val reportId: Long? = null,
    @field:Schema(description = "快速 RCA ID；仅 QUICK_RCA_READY 事件返回", example = "42")
    val rcaId: Long? = null,
    @field:Schema(description = "关联反馈来源 ID；用于受控详情跳转", example = "101")
    val feedbackId: Long? = null,
    @field:Schema(description = "关联 Incident ID；无活动关联时为空", example = "30")
    val incidentId: Long? = null,
    @field:Schema(description = "关联 Incident 标题；无关联时为空")
    val incidentTitle: String? = null,
    @field:Schema(description = "关联 Incident 状态枚举；无关联时为空", example = "OPEN")
    val incidentStatus: String? = null,
    @field:Schema(description = "同一 Incident 当前活动告警数", example = "2")
    val relatedAlertCount: Long = 0,
    @field:Schema(description = "当前用户是否已收到同告警的可见源告警通知")
    val hasVisibleAlertNotification: Boolean = false,
    @field:Schema(description = "源告警实例 ID；源记录不存在时为空", example = "20")
    val sourceAlertId: Long? = null,
    @field:Schema(description = "源告警触发时间；数据库本地时区")
    val sourceAlertFiredAt: LocalDateTime? = null,
    @field:Schema(description = "源告警状态枚举", example = "FIRING")
    val sourceAlertStatus: String? = null,
    @field:Schema(description = "源告警严重度枚举", example = "P1")
    val sourceAlertSeverity: String? = null,
    @field:Schema(description = "源告警规则名称")
    val sourceAlertTitle: String? = null,
    @field:Schema(description = "告警规则编码；非告警通知为空", example = "RULE_1")
    val ruleCode: String? = null,
    @field:Schema(description = "通知事件类型枚举", example = "ALERT_FIRED")
    val eventType: String,
    @field:Schema(description = "通知标题")
    val title: String,
    @field:Schema(description = "通知摘要；已按通知生成时的字段策略固化")
    val summary: String? = null,
    @field:Schema(description = "通知严重度枚举", example = "P2")
    val severity: String,
    @field:Schema(description = "通知事实创建时间；数据库本地时区")
    val createdAt: LocalDateTime,
    @field:Schema(description = "送达时间；非投递型业务事件等于创建时间")
    val deliveredAt: LocalDateTime? = null,
    @field:Schema(description = "当前用户读取时间；未读时为空")
    val readAt: LocalDateTime? = null,
    @field:Schema(description = "当前用户是否已读")
    val read: Boolean,
    @field:Schema(description = "前端站内受控跳转路径；无目标时为空")
    val targetPath: String? = null,
)

@Schema(description = "站内通知分页响应")
data class InAppNotificationPage(
    @field:Schema(description = "当前页通知，按创建时间倒序")
    val records: List<InAppNotificationItem>,
    @field:Schema(description = "当前筛选口径总数", example = "42")
    val total: Long,
    @field:Schema(description = "页码，从 1 开始", example = "1")
    val page: Int,
    @field:Schema(description = "每页条数，范围 1..100", example = "20")
    val size: Int,
    @field:Schema(description = "当前用户全部五类未读通知总数", example = "9")
    val unreadCount: Long,
)

@Schema(description = "站内通知未读计数")
data class InAppUnreadCountResponse(@field:Schema(description = "当前用户全部五类未读总数") val count: Long)
@Schema(description = "单条站内通知已读结果")
data class InAppReadResponse(@field:Schema(description = "完成可见性校验并幂等记录已读后为 true") val read: Boolean)
@Schema(description = "全部标为已读结果")
data class InAppMarkAllReadResponse(@field:Schema(description = "本次新写入的已读回执数") val marked: Int)

/**
 * 聚合告警、反馈交接、催办、处置状态和产品反馈五类站内通知。
 * Repository 已完成单表分段读取和批量关联，本服务只负责稳定 JSON 合同与全局时间排序。
 */
@Service
class InAppNotificationService(
    private val repository: InAppNotificationRepository,
    private val mapper: ObjectMapper,
) {
    fun list(userId: Long, page: Int, size: Int, unreadOnly: Boolean): InAppNotificationPage {
        ensureAlert(page >= 1 && size in 1..100) { "IN_APP_NOTIFICATION_PAGE_INVALID" }
        val window = Math.multiplyExact(page, size)
        val alertRows = repository.alertRows(userId, window, unreadOnly)
        val alertContext = repository.alertContext(userId, alertRows.mapNotNull { it.value.alertInstanceId })
        val handoffRows = repository.handoffRows(userId, window, unreadOnly)
        val reminderRows = repository.reminderRows(userId, window, unreadOnly)
        val dispositionRows = repository.dispositionRows(userId, window, unreadOnly)
        val productRows = repository.productRows(userId, window, unreadOnly)

        val actorNames = repository.userNames(
            handoffRows.map { it.value.actorId } +
                reminderRows.map { it.value.actorId } +
                dispositionRows.map { it.value.actorId } +
                productRows.map { it.value.actorId },
        )
        val requests = repository.requests(productRows.map { it.value.requestId })

        val records = buildList {
            addAll(alertRows.map { row -> alertItem(row, alertContext) })
            addAll(handoffRows.map { row -> handoffItem(row, actorNames[row.value.actorId]) })
            addAll(reminderRows.map { row -> reminderItem(row, actorNames[row.value.actorId]) })
            addAll(dispositionRows.map { row -> dispositionItem(row, actorNames[row.value.actorId]) })
            addAll(productRows.mapNotNull { row ->
                val request = requests[row.value.requestId] ?: return@mapNotNull null
                productItem(row, actorNames[row.value.actorId], request.requestNo, request.title)
            })
        // Kotlin 的稳定排序保留原聚合顺序，确保相同 createdAt 时仍按
        // 告警、交接、催办、处置、产品反馈的既有优先级分页。
        }.sortedByDescending { it.createdAt }
            .drop((page - 1) * size)
            .take(size)

        val unreadCount = repository.unreadCount(userId)
        return InAppNotificationPage(
            records = records,
            total = if (unreadOnly) unreadCount else repository.total(userId),
            page = page,
            size = size,
            unreadCount = unreadCount,
        )
    }

    fun unreadCount(userId: Long): Long = repository.unreadCount(userId)

    @Transactional
    fun markRead(userId: Long, notificationId: Long): Boolean {
        repository.markRead(userId, notificationId)
        return true
    }

    @Transactional
    fun markAllRead(userId: Long): Int = repository.markAllRead(userId)

    private fun alertItem(
        row: InAppReadRow<InAppAlertNotificationPo>,
        context: com.feedback.repository.alert.InAppAlertContext,
    ): InAppNotificationItem {
        val value = row.value
        val content = parse(value.content)
        val eventType = content.text("eventType") ?: value.eventType ?: "ALERT_NOTIFICATION"
        val alertId = value.alertInstanceId
        val incident = alertId?.let(context.incidentByAlert::get)
        val sourceAlert = alertId?.let(context.alertById::get)
        val sourceRule = sourceAlert?.ruleId?.let(context.ruleById::get)
        val rcaId = content.long("rcaId")?.takeIf { it > 0 && eventType == "QUICK_RCA_READY" }
        val feedbackId = content.long("feedbackId")?.takeIf { it > 0 && eventType == "FEEDBACK_HANDOFF" }
        return InAppNotificationItem(
            id = value.id,
            alertInstanceId = alertId,
            reportId = value.reportId,
            rcaId = rcaId,
            feedbackId = feedbackId,
            incidentId = incident?.id,
            incidentTitle = incident?.title,
            incidentStatus = incident?.status,
            relatedAlertCount = alertId?.let { context.relatedCountByAlert[it] } ?: 0,
            hasVisibleAlertNotification = alertId != null && alertId in context.visibleSourceAlerts,
            sourceAlertId = sourceAlert?.id,
            sourceAlertFiredAt = sourceAlert?.firedAt,
            sourceAlertStatus = sourceAlert?.status,
            sourceAlertSeverity = sourceAlert?.severity,
            sourceAlertTitle = sourceRule?.name,
            ruleCode = value.ruleCode,
            eventType = eventType,
            title = content.text("title") ?: "站内通知",
            summary = content.text("summary") ?: "",
            severity = content.text("severity") ?: "P3",
            createdAt = value.createdAt,
            deliveredAt = value.deliveredAt,
            readAt = row.readAt,
            read = row.readAt != null,
            targetPath = when {
                feedbackId != null -> "/channel/$feedbackId"
                rcaId != null -> "/report/rca?id=$rcaId"
                alertId != null -> "/alert/center?alertId=$alertId"
                value.reportId != null -> "/report/list?reportId=${value.reportId}"
                else -> null
            },
        )
    }

    private fun handoffItem(row: InAppReadRow<InAppHandoffPo>, actor: String?) = InAppNotificationItem(
        id = -row.value.id,
        feedbackId = row.value.feedbackId,
        eventType = "FEEDBACK_HANDOFF",
        title = "反馈任务交接",
        summary = "${actor ?: "未知用户"} 向你交接反馈 #${row.value.feedbackId}：${row.value.reason}",
        severity = "P2",
        createdAt = row.value.createdAt,
        deliveredAt = row.value.createdAt,
        readAt = row.readAt,
        read = row.readAt != null,
        targetPath = "/channel/${row.value.feedbackId}",
    )

    private fun reminderItem(row: InAppReadRow<InAppReminderPo>, actor: String?) = InAppNotificationItem(
        id = -3_000_000_000L - row.value.id,
        feedbackId = row.value.feedbackId,
        eventType = "FEEDBACK_REMINDER",
        title = if (row.value.reminderType == "ACCEPTANCE") "任务接收提醒" else "任务处理催办",
        summary = "${actor ?: "未知用户"} 提醒你处理反馈 #${row.value.feedbackId}：${row.value.message}",
        severity = "P2",
        createdAt = row.value.createdAt,
        deliveredAt = row.value.createdAt,
        readAt = row.readAt,
        read = row.readAt != null,
        targetPath = "/channel/${row.value.feedbackId}",
    )

    private fun dispositionItem(row: InAppReadRow<InAppDispositionPo>, actor: String?) = InAppNotificationItem(
        id = -1_000_000_000L - row.value.id,
        feedbackId = row.value.feedbackId,
        eventType = "FEEDBACK_STATUS_CHANGED",
        title = if (row.value.eventType == "REOPENED") "反馈已重新打开" else "反馈状态已更新",
        summary = "${actor ?: "未知用户"} 将反馈 #${row.value.feedbackId} 从 ${row.value.oldStatus} 更新为 ${row.value.newStatus}：${row.value.reason}",
        severity = "P3",
        createdAt = row.value.createdAt,
        deliveredAt = row.value.createdAt,
        readAt = row.readAt,
        read = row.readAt != null,
        targetPath = "/channel/${row.value.feedbackId}",
    )

    private fun productItem(
        row: InAppReadRow<InAppProductEventPo>,
        actor: String?,
        requestNo: String,
        requestTitle: String,
    ) = InAppNotificationItem(
        id = -2_000_000_000L - row.value.id,
        eventType = "PRODUCT_FEEDBACK_TASK",
        title = if (row.value.eventType == "SUBMITTED_FOR_ACCEPTANCE") "需求等待你验收" else "需求任务已更新",
        summary = "${actor ?: "未知用户"} 更新了 $requestNo $requestTitle：${row.value.reason}",
        severity = "P3",
        createdAt = row.value.createdAt,
        deliveredAt = row.value.createdAt,
        readAt = row.readAt,
        read = row.readAt != null,
        targetPath = "/tasks/product-feedback?requestId=${row.value.requestId}",
    )

    private fun parse(content: String?): JsonNode? = content?.let { runCatching { mapper.readTree(it) }.getOrNull() }
    private fun JsonNode?.text(field: String): String? = this?.get(field)?.takeUnless { it.isNull }?.asText()
    private fun JsonNode?.long(field: String): Long? = this?.get(field)?.takeUnless { it.isNull }?.asLong()
}
