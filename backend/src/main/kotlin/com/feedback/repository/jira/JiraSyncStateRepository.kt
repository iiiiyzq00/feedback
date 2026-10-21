package com.feedback.repository.jira

import com.feedback.common.SafeErrorDetail
import com.feedback.common.exception.AbstractException
import com.feedback.mapper.jira.JiraSyncStateMapper
import com.feedback.model.po.jira.JiraSyncStatePo
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class JiraSyncState(
    var status: String = "",
    var watermark: LocalDateTime? = null,
    var requestedFrom: LocalDateTime? = null,
    var requestedTo: LocalDateTime? = null,
    var requestedTrigger: String? = null,
    var requestedBy: Long? = null,
    var lastAttemptAt: LocalDateTime? = null,
    var lastSuccessAt: LocalDateTime? = null,
    var nextRunAt: LocalDateTime? = null,
    var lastBatchId: Long? = null,
    var failureCount: Int = 0,
    var lastErrorCode: String? = null,
    var lastErrorDetail: String? = null
)

/** Jira 同步状态的经典 Repository 边界；租约条件更新与随后的当前状态读取保持同一事务。 */
@Repository
class JiraSyncStateRepository(private val mapper: JiraSyncStateMapper) {
    fun get(key: String) = mapper.find(key)?.toDomain()

    @Transactional
    fun ensure(key: String, hash: ByteArray, enabled: Boolean) {
        val status = if (enabled) "IDLE" else "DISABLED"
        if (mapper.insertIgnore(key, hash, status) == 0) mapper.refresh(key, hash, status)
    }

    fun enqueue(key: String, trigger: String, from: LocalDateTime?, to: LocalDateTime?, actor: Long?) = mapper.enqueue(key, trigger, from, to, actor) == 1
    fun enqueueScheduledIfDue(key: String) = mapper.enqueueScheduledIfDue(key) == 1

    @Transactional
    fun claim(key: String, worker: String): JiraSyncState? =
        if (mapper.claim(key, worker) == 1) mapper.find(key)?.toDomain() else null

    fun heartbeat(key: String, worker: String) = mapper.heartbeat(key, worker) == 1

    fun complete(key: String, worker: String, watermark: LocalDateTime, batchId: Long?, interval: Int) {
        mapper.complete(key, worker, watermark, batchId, interval)
    }

    @Transactional
    fun fail(key: String, worker: String, error: Throwable) {
        val governed = error as? AbstractException
        val currentFailures = mapper.find(key)?.failureCount ?: 0
        val retryAfter = (governed?.details?.get("retryAfterSeconds") as? Number)?.toLong()
        val delaySeconds = if (governed?.errorCode?.retryable != false) {
            retryAfter?.coerceIn(1, 3600)
                ?: ((1L shl (currentFailures + 1).coerceAtMost(5)) * 60L).coerceAtMost(3600L)
        } else null
        mapper.fail(
            key,
            worker,
            delaySeconds,
            (governed?.errorCode?.name ?: "INTERNAL_ERROR").take(50),
            SafeErrorDetail.summary(error, "JIRA_SYNC").take(500)
        )
    }

    private fun JiraSyncStatePo.toDomain() = JiraSyncState(
        status = status,
        watermark = watermark,
        requestedFrom = requestedFrom,
        requestedTo = requestedTo,
        requestedTrigger = requestedTrigger,
        requestedBy = requestedBy,
        lastAttemptAt = lastAttemptAt,
        lastSuccessAt = lastSuccessAt,
        nextRunAt = nextRunAt,
        lastBatchId = lastBatchId,
        failureCount = failureCount,
        lastErrorCode = lastErrorCode,
        lastErrorDetail = lastErrorDetail
    )
}
