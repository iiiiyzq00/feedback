package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.DeadLetterNotificationPo
import com.feedback.model.po.alert.DeadLetterSummaryPo
import com.feedback.model.po.alert.DeliveryEvidenceInsertPo
import com.feedback.repository.alert.DeadLetterRepository
import com.feedback.security.WebPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class DeadLetterAction(val reason: String)
data class DeliveryEvidenceInput(val type: String, val reference: String, val sha256: String? = null, val note: String? = null)

@Service
class DeadLetterService(private val repository: DeadLetterRepository, private val mapper: ObjectMapper) {
    fun list(page: Int, size: Int, channel: String?): Map<String, Any> {
        ensureAlert(page >= 1 && size in 1..100)
        val normalizedChannel = channel?.uppercase()?.takeIf { it in NotificationPolicy.CHANNELS }
        val rows = repository.list(normalizedChannel, size, (page - 1) * size).map(::summary)
        return mapOf("records" to rows, "total" to repository.count(normalizedChannel), "page" to page, "size" to size)
    }

    fun detail(id: Long): Map<String, Any?> {
        val notification = repository.notification(id) ?: alertMissing("NOTIFICATION_NOT_FOUND")
        return detail(notification).apply {
            this["attemptHistory"] = repository.attempts(id)
            this["evidence"] = repository.evidence(id)
        }
    }

    @Transactional
    fun replay(id: Long, input: DeadLetterAction): Map<String, Any> {
        validateReason(input.reason)
        val actor = actor()
        if (!repository.replay(id)) alertAbort("DEAD_LETTER_NOT_REPLAYABLE")
        audit(actor, "ALERT_NOTIFICATION_REPLAY", id, input.reason)
        return mapOf("id" to id, "replayed" to true)
    }

    @Transactional
    fun ignore(id: Long, input: DeadLetterAction): Map<String, Any> {
        validateReason(input.reason)
        val actor = actor()
        if (!repository.ignore(id)) alertAbort("DEAD_LETTER_NOT_OPEN")
        audit(actor, "ALERT_NOTIFICATION_IGNORE", id, input.reason)
        return mapOf("id" to id, "ignored" to true)
    }

    fun addEvidence(id: Long, input: DeliveryEvidenceInput): Map<String, Any> {
        ensureAlert(input.type in setOf("SCREENSHOT", "PROVIDER_RECEIPT", "USER_CONFIRMATION"))
        ensureAlert(input.reference.isNotBlank() && input.reference.length <= 1000)
        ensureAlert(input.sha256 == null || input.sha256.matches(Regex("^[a-fA-F0-9]{64}$")))
        ensureAlert(input.note == null || input.note.length <= 500)
        val actor = actor()
        if (!repository.exists(id)) alertMissing("NOTIFICATION_NOT_FOUND")
        val row = DeliveryEvidenceInsertPo(actor = actor, target = id.toString(), type = input.type,
            reference = input.reference.take(1000), sha256 = input.sha256?.lowercase(), note = input.note)
        return mapOf("id" to repository.addEvidence(row))
    }

    private fun validateReason(value: String) = ensureAlert(value.isNotBlank() && value.length <= 200) { "处置原因长度必须为1到200" }
    private fun actor() = (SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId ?: alertStateAbort("未认证")
    private fun audit(actor: Long, operation: String, id: Long, reason: String) =
        repository.audit(actor, operation, id, mapper.writeValueAsString(mapOf("reason" to reason.take(200))))

    private fun summary(row: DeadLetterSummaryPo) = linkedMapOf<String, Any?>(
        "id" to row.id, "ruleCode" to row.ruleCode, "alertInstanceId" to row.alertInstanceId, "channel" to row.channel,
        "attempts" to row.attempts, "deadLetterStatus" to row.deadLetterStatus, "httpStatus" to row.httpStatus,
        "providerCode" to row.providerCode, "errorType" to row.errorType, "errorSummary" to row.errorSummary,
        "deadLetteredAt" to row.deadLetteredAt, "replayCount" to row.replayCount,
    )

    private fun detail(row: DeadLetterNotificationPo) = linkedMapOf<String, Any?>(
        "id" to row.id, "ruleId" to row.ruleId, "ruleCode" to row.ruleCode, "alertInstanceId" to row.alertInstanceId,
        "channel" to row.channel, "status" to row.status, "attempts" to row.attempts, "maxRetries" to row.maxRetries,
        "deadLetterStatus" to row.deadLetterStatus, "deadLetteredAt" to row.deadLetteredAt,
        "providerMessageId" to row.providerMessageId, "httpStatus" to row.httpStatus, "providerCode" to row.providerCode,
        "errorType" to row.errorType, "errorSummary" to row.errorSummary, "replayCount" to row.replayCount,
        "createdAt" to row.createdAt, "updatedAt" to row.updatedAt,
    )
}
