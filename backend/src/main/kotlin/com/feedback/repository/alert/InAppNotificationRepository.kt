package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

data class InAppReadRow<T>(val value: T, val readAt: LocalDateTime?)

data class InAppAlertContext(
    val incidentByAlert: Map<Long, InAppIncidentPo>,
    val relatedCountByAlert: Map<Long, Long>,
    val alertById: Map<Long, InAppAlertPo>,
    val ruleById: Map<Long, InAppRulePo>,
    val visibleSourceAlerts: Set<Long>,
)

/**
 * 站内通知持久化边界。每个 Mapper 只访问一张表；跨表可见性、已读回执和展示上下文
 * 都在 Repository 中按不超过 500 条的批次组装，避免 JOIN、相关子查询和 N+1。
 */
@Repository
class InAppNotificationRepository(
    private val alerts: InAppAlertNotificationMapper,
    private val handoffs: InAppHandoffMapper,
    private val reminders: InAppReminderMapper,
    private val dispositions: InAppDispositionMapper,
    private val assignments: InAppAssignmentMapper,
    private val productEvents: InAppProductEventMapper,
    private val productRequests: InAppProductRequestMapper,
    private val users: InAppUserMapper,
    private val receipts: InAppReceiptMapper,
    private val incidentLinks: InAppIncidentLinkMapper,
    private val incidents: InAppIncidentMapper,
    private val alertInstances: InAppAlertMapper,
    private val rules: InAppRuleMapper,
) {
    private val batchSize = 500

    fun alertRows(user: Long, limit: Int, unreadOnly: Boolean) = collect(
        user = user,
        type = "ALERT_NOTIFICATION",
        limit = limit,
        unreadOnly = unreadOnly,
        fetch = { before -> alerts.page(user, before, batchSize) },
        id = { it.id },
    )

    fun handoffRows(user: Long, limit: Int, unreadOnly: Boolean) = collect(
        user = user,
        type = "FEEDBACK_HANDOFF_TASK",
        limit = limit,
        unreadOnly = unreadOnly,
        fetch = { before -> handoffs.page(user, before, batchSize) },
        id = { it.id },
    )

    fun reminderRows(user: Long, limit: Int, unreadOnly: Boolean) = collect(
        user = user,
        type = "FEEDBACK_REMINDER",
        limit = limit,
        unreadOnly = unreadOnly,
        fetch = { before -> reminders.page(user, before, batchSize) },
        id = { it.id },
    )

    fun dispositionRows(user: Long, limit: Int, unreadOnly: Boolean) = collect(
        user = user,
        type = "FEEDBACK_DISPOSITION_EVENT",
        limit = limit,
        unreadOnly = unreadOnly,
        fetch = { before -> dispositions.page(user, before, batchSize) },
        id = { it.id },
        eligible = { rows ->
            val ownerBySource = assignmentOwners(rows.map { it.feedbackId })
            rows.filter { ownerBySource[it.feedbackId] == user }
        },
    )

    fun productRows(user: Long, limit: Int, unreadOnly: Boolean) = collect(
        user = user,
        type = "PRODUCT_FEEDBACK_EVENT",
        limit = limit,
        unreadOnly = unreadOnly,
        fetch = { before -> productEvents.page(user, before, batchSize) },
        id = { it.id },
        eligible = { rows ->
            val requestById = requests(rows.map { it.requestId })
            rows.filter { row ->
                val request = requestById[row.requestId] ?: return@filter false
                (row.newHandlerUserId == user && row.actorId != user) ||
                    (request.createdBy == user && row.eventType in acceptanceEvents)
            }
        },
    )

    fun total(user: Long): Long =
        alerts.count(user) + handoffs.count(user) + reminders.count(user) +
            eligibleCount({ before -> dispositions.page(user, before, batchSize) }) { rows ->
                val ownerBySource = assignmentOwners(rows.map { it.feedbackId })
                rows.count { ownerBySource[it.feedbackId] == user }
            } +
            eligibleCount({ before -> productEvents.page(user, before, batchSize) }) { rows ->
                val requestById = requests(rows.map { it.requestId })
                rows.count { row ->
                    val request = requestById[row.requestId] ?: return@count false
                    (row.newHandlerUserId == user && row.actorId != user) ||
                        (request.createdBy == user && row.eventType in acceptanceEvents)
                }
            }

    fun unreadCount(user: Long): Long =
        unreadCount(user, "ALERT_NOTIFICATION", { before -> alerts.page(user, before, batchSize) }, { it.id }) +
            unreadCount(user, "FEEDBACK_HANDOFF_TASK", { before -> handoffs.page(user, before, batchSize) }, { it.id }) +
            unreadCount(user, "FEEDBACK_REMINDER", { before -> reminders.page(user, before, batchSize) }, { it.id }) +
            unreadCount(
                user,
                "FEEDBACK_DISPOSITION_EVENT",
                { before -> dispositions.page(user, before, batchSize) },
                { it.id },
            ) { rows ->
                val ownerBySource = assignmentOwners(rows.map { it.feedbackId })
                rows.filter { ownerBySource[it.feedbackId] == user }
            } +
            unreadCount(
                user,
                "PRODUCT_FEEDBACK_EVENT",
                { before -> productEvents.page(user, before, batchSize) },
                { it.id },
            ) { rows ->
                val requestById = requests(rows.map { it.requestId })
                rows.filter { row ->
                    val request = requestById[row.requestId] ?: return@filter false
                    (row.newHandlerUserId == user && row.actorId != user) ||
                        (request.createdBy == user && row.eventType in acceptanceEvents)
                }
            }

    fun userNames(ids: Collection<Long>): Map<Long, String> = ids.distinct().chunked(batchSize)
        .flatMap(users::byIds)
        .associate { it.id to (it.displayName?.takeIf(String::isNotBlank) ?: it.username) }

    fun requests(ids: Collection<Long>): Map<Long, InAppProductRequestPo> = ids.distinct().chunked(batchSize)
        .flatMap(productRequests::byIds)
        .associateBy { it.id }

    fun alertContext(user: Long, ids: Collection<Long>): InAppAlertContext {
        val alertIds = ids.distinct()
        if (alertIds.isEmpty()) return InAppAlertContext(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptySet())
        val links = alertIds.chunked(batchSize).flatMap(incidentLinks::byAlerts)
        val incidentIdByAlert = links.groupBy { it.alertId }.mapValues { (_, rows) -> rows.maxOf { it.incidentId } }
        val incidentIds = incidentIdByAlert.values.distinct()
        val incidentById = incidentIds.chunked(batchSize).flatMap(incidents::byIds).associateBy { it.id }
        val relatedByIncident = incidentIds.chunked(batchSize).flatMap(incidentLinks::counts).associate { it.incidentId to it.count }
        val alertById = alertIds.chunked(batchSize).flatMap(alertInstances::byIds).associateBy { it.id }
        val ruleById = alertById.values.map { it.ruleId }.distinct().chunked(batchSize).flatMap(rules::byIds).associateBy { it.id }
        val visible = alertIds.chunked(batchSize).flatMap { alerts.visibleAlertIds(user, it) }.toSet()
        return InAppAlertContext(
            incidentByAlert = incidentIdByAlert.mapNotNull { (alertId, incidentId) ->
                incidentById[incidentId]?.let { alertId to it }
            }.toMap(),
            relatedCountByAlert = incidentIdByAlert.mapValues { (_, incidentId) -> relatedByIncident[incidentId] ?: 0 },
            alertById = alertById,
            ruleById = ruleById,
            visibleSourceAlerts = visible,
        )
    }

    fun markRead(user: Long, notificationId: Long) {
        val target = target(notificationId)
        val visible = when (target.type) {
            "ALERT_NOTIFICATION" -> alerts.visible(target.id, user) == 1
            "FEEDBACK_HANDOFF_TASK" -> handoffs.visible(target.id, user) == 1
            "FEEDBACK_REMINDER" -> reminders.visible(target.id, user) == 1
            "FEEDBACK_DISPOSITION_EVENT" -> dispositionVisible(target.id, user)
            "PRODUCT_FEEDBACK_EVENT" -> productVisible(target.id, user)
            else -> false
        }
        ensureAlertRepositoryInput(visible) { "IN_APP_NOTIFICATION_NOT_FOUND" }
        receipts.insert(user, target.type, target.id.toString(), target.key(user))
    }

    fun markAllRead(user: Long): Int {
        var marked = 0
        forEachVisibleBatch(user) { rows -> marked += receipts.insertBatch(rows) }
        return marked
    }

    private fun dispositionVisible(id: Long, user: Long): Boolean {
        val row = dispositions.visible(id) ?: return false
        return row.actorId != user && assignmentOwners(listOf(row.feedbackId))[row.feedbackId] == user
    }

    private fun productVisible(id: Long, user: Long): Boolean {
        val row = productEvents.visible(id) ?: return false
        val request = requests(listOf(row.requestId))[row.requestId] ?: return false
        return (row.newHandlerUserId == user && row.actorId != user) ||
            (request.createdBy == user && row.eventType in acceptanceEvents)
    }

    private fun forEachVisibleBatch(user: Long, consume: (List<InAppReceiptInsertPo>) -> Unit) {
        scan({ before -> alerts.page(user, before, batchSize) }, { it.id }) { rows ->
            consume(rows.map { receipt(user, "ALERT_NOTIFICATION", it.id, "${it.id}:$user") })
        }
        scan({ before -> handoffs.page(user, before, batchSize) }, { it.id }) { rows ->
            consume(rows.map { receipt(user, "FEEDBACK_HANDOFF_TASK", it.id, "handoff:${it.id}:$user") })
        }
        scan({ before -> reminders.page(user, before, batchSize) }, { it.id }) { rows ->
            consume(rows.map { receipt(user, "FEEDBACK_REMINDER", it.id, "feedback-reminder:${it.id}:$user") })
        }
        scan({ before -> dispositions.page(user, before, batchSize) }, { it.id }) { rows ->
            val owners = assignmentOwners(rows.map { it.feedbackId })
            rows.filter { owners[it.feedbackId] == user }
                .map { receipt(user, "FEEDBACK_DISPOSITION_EVENT", it.id, "disposition:${it.id}:$user") }
                .takeIf { it.isNotEmpty() }
                ?.let(consume)
        }
        scan({ before -> productEvents.page(user, before, batchSize) }, { it.id }) { rows ->
            val requestById = requests(rows.map { it.requestId })
            rows.filter { row ->
                val request = requestById[row.requestId] ?: return@filter false
                (row.newHandlerUserId == user && row.actorId != user) ||
                    (request.createdBy == user && row.eventType in acceptanceEvents)
            }.map { receipt(user, "PRODUCT_FEEDBACK_EVENT", it.id, "product-feedback:${it.id}:$user") }
                .takeIf { it.isNotEmpty() }
                ?.let(consume)
        }
    }

    private fun receipt(user: Long, type: String, id: Long, key: String) =
        InAppReceiptInsertPo(userId = user, type = type, targetId = id.toString(), key = key)

    private fun assignmentOwners(ids: Collection<Long>): Map<Long, Long> = ids.distinct().chunked(batchSize)
        .flatMap(assignments::owners)
        .associate { it.feedbackId to it.ownerId }

    private fun <T> collect(
        user: Long,
        type: String,
        limit: Int,
        unreadOnly: Boolean,
        fetch: (Long?) -> List<T>,
        id: (T) -> Long,
        eligible: (List<T>) -> List<T> = { it },
    ): List<InAppReadRow<T>> {
        val result = mutableListOf<InAppReadRow<T>>()
        var before: Long? = null
        while (result.size < limit) {
            val raw = fetch(before)
            if (raw.isEmpty()) break
            before = raw.minOf(id)
            val rows = eligible(raw)
            val readById = receiptMap(user, type, rows.map(id))
            rows.forEach { row ->
                val readAt = readById[id(row)]
                if (!unreadOnly || readAt == null) result += InAppReadRow(row, readAt)
            }
            if (raw.size < batchSize) break
        }
        return result.take(limit)
    }

    private fun <T> unreadCount(
        user: Long,
        type: String,
        fetch: (Long?) -> List<T>,
        id: (T) -> Long,
        eligible: (List<T>) -> List<T> = { it },
    ): Long {
        var total = 0L
        scan(fetch, id) { raw ->
            val rows = eligible(raw)
            val readIds = receiptMap(user, type, rows.map(id)).keys
            total += rows.count { id(it) !in readIds }
        }
        return total
    }

    private fun <T> eligibleCount(fetch: (Long?) -> List<T>, count: (List<T>) -> Int): Long {
        var total = 0L
        scan(fetch, { row ->
            when (row) {
                is InAppDispositionPo -> row.id
                is InAppProductEventPo -> row.id
                else -> alertRepositoryAbort("UNSUPPORTED_IN_APP_NOTIFICATION_ROW")
            }
        }) { total += count(it) }
        return total
    }

    private fun <T> scan(fetch: (Long?) -> List<T>, id: (T) -> Long, consume: (List<T>) -> Unit) {
        var before: Long? = null
        while (true) {
            val rows = fetch(before)
            if (rows.isEmpty()) return
            consume(rows)
            before = rows.minOf(id)
            if (rows.size < batchSize) return
        }
    }

    private fun receiptMap(user: Long, type: String, ids: Collection<Long>): Map<Long, LocalDateTime?> {
        if (ids.isEmpty()) return emptyMap()
        return ids.distinct().chunked(batchSize).flatMap { chunk ->
            receipts.byTargets(user, type, chunk.map(Long::toString))
        }.mapNotNull { row -> row.targetId.toLongOrNull()?.let { it to row.readAt } }.toMap()
    }

    private data class ReadTarget(val type: String, val id: Long, val keyPrefix: String) {
        fun key(user: Long) = when (keyPrefix) {
            "" -> "$id:$user"
            else -> "$keyPrefix:$id:$user"
        }
    }

    private fun target(notificationId: Long): ReadTarget = when {
        notificationId <= -3_000_000_000L -> ReadTarget("FEEDBACK_REMINDER", -notificationId - 3_000_000_000L, "feedback-reminder")
        notificationId <= -2_000_000_000L -> ReadTarget("PRODUCT_FEEDBACK_EVENT", -notificationId - 2_000_000_000L, "product-feedback")
        notificationId <= -1_000_000_000L -> ReadTarget("FEEDBACK_DISPOSITION_EVENT", -notificationId - 1_000_000_000L, "disposition")
        notificationId < 0 -> ReadTarget("FEEDBACK_HANDOFF_TASK", -notificationId, "handoff")
        else -> ReadTarget("ALERT_NOTIFICATION", notificationId, "")
    }

    private companion object {
        val acceptanceEvents = setOf("SUBMITTED_FOR_ACCEPTANCE", "ACCEPTANCE_REJECTED")
    }
}
