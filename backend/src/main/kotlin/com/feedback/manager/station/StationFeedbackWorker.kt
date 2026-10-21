package com.feedback.manager.station

import com.feedback.model.station.PreparedStationAttachment
import com.feedback.repository.remote.station.StationAttachmentDownloader
import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.AbstractException
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import com.feedback.common.exception.ServiceException
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import com.feedback.repository.station.online.StationOnlineChannelRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/** Station 在线证据 Worker；租约续期包围外部查询和附件下载，最终状态短事务提交。 */
@Service
class StationFeedbackWorker(
    private val channels: StationOnlineChannelRepository,
    private val phoneResolver: StationPhoneResolver,
    private val objectMapper: ObjectMapper,
    private val attachmentDownloader: StationAttachmentDownloader,
    private val finalization: StationOnlineFinalizationService,
    @Value("\${app.station-feedback.worker-id:}") configuredWorkerId: String
) {
    private val workerId = configuredWorkerId.ifBlank { "station-${UUID.randomUUID()}" }.take(100)
    private val leaseHeartbeat = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "station-lease-heartbeat").apply { isDaemon = true }
    }

    @Transactional
    /** 按稳定顺序锁定并领取一个可处理任务；租约 owner 是后续续租和状态推进的必要条件。 */
    fun claimOne(): Long? = channels.claimOne(workerId)?.sourceId

    @Transactional
    /** 以当前版本和租约条件领取记录；竞争失败返回未领取，不覆盖其他 Worker 的租约。 */
    fun claim(sourceId: Long): Boolean = channels.claim(sourceId, workerId)

    /** 处理一个可领取任务；外部 HTTP、文件和高成本计算位于事务外，状态提交使用 owner 条件更新。 */
    fun processOne(sourceId: Long) {
        val row = channels.get(sourceId)
        if (row.leaseOwner != workerId || row.ingestStatus != "PROCESSING") {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_CHANNEL_LEASE_LOST")
        }
        val prepared = mutableListOf<PreparedStationAttachment>()
        var finalized = false
        try {
            var resolvedPhone: ResolvedStationPhone? = null
            var contactUnavailable = false
            if (row.contactStatus == "NOT_PROVIDED" || row.contactStatus == "NOT_PROCESSED") {
                try {
                    resolvedPhone = withLeaseRenewal(sourceId) {
                        phoneResolver.resolve(
                            row.sourceApiVersion,
                            row.sourcePhoneCiphertext,
                            row.authenticatedUserId,
                            row.authenticatedProductCode
                        )
                    }
                } catch (error: ClientException) {
                    if (!isTerminalStationContactUnavailable(error)) {
                        throw error
                    }
                    contactUnavailable = true
                }
            }
            var unavailableAttachments = 0
            if (!row.pendingPhotoUrls.isNullOrBlank()) {
                val urls = objectMapper.readTree(row.pendingPhotoUrls)
                if (!urls.isArray || urls.size() > 9) {
                    throw ClientException(ErrorCode.BAD_REQUEST, "STATION_ATTACHMENT_URLS_INVALID")
                }
                val candidates = mutableListOf<StationAttachmentCandidate>()
                urls.forEachIndexed { index, node ->
                    if (!node.isTextual) {
                        throw ClientException(ErrorCode.BAD_REQUEST, "STATION_ATTACHMENT_URL_INVALID")
                    }
                    try {
                        val uri = attachmentDownloader.validateUrl(node.textValue())
                        candidates += StationAttachmentCandidate(
                            node.textValue(), index, attachmentDownloader.sourceIdentity(sourceId, index, uri)
                        )
                    } catch (error: AbstractException) {
                        if (isTerminalStationEvidenceUnavailable(error)) {
                            unavailableAttachments++
                        } else {
                            throw error
                        }
                    }
                }
                val existingIdentities = finalization.existingAttachmentIdentities(candidates.map { it.sourceIdentity })
                candidates.filterNot { it.sourceIdentity in existingIdentities }.forEach { candidate ->
                    try {
                        prepared += withLeaseRenewal(sourceId, PreparedStationAttachment::discard) {
                            attachmentDownloader.prepare(sourceId, candidate.url, candidate.ordinal)
                        }
                    } catch (error: AbstractException) {
                        if (isTerminalStationEvidenceUnavailable(error)) {
                            unavailableAttachments++
                        } else {
                            throw error
                        }
                    }
                }
            }
            finalization.finalizeSuccess(
                sourceId,
                workerId,
                resolvedPhone,
                contactUnavailable,
                prepared,
                unavailableAttachments
            )
            finalized = true
        } catch (error: ServiceException) {
            if (error.errorCode == ErrorCode.STATION_DIRTY_MARKING_FAILED) {
                throw error
            }
            fail(sourceId, row, error)
        } catch (error: Exception) {
            fail(sourceId, row, error)
        } finally {
            if (!finalized) {
                prepared.forEach(PreparedStationAttachment::discard)
            }
        }
    }

    /** 按当前状态或租约条件记录失败的 Station 链路；单次处理受批量和重试上限约束，外部 I/O 不占用数据库事务。 */
    private fun fail(sourceId: Long, row: com.feedback.model.po.station.online.StationOnlineChannelPo, error: Exception) {
        val retry = row.retryCount + 1
        val max = row.maxRetryCount
        val businessFailure = error is ClientException
        val status = if (businessFailure) "REVIEW_NEEDED" else if (retry >= max) "FAILED_FINAL" else "RETRY_PENDING"
        val delay = (5L shl (retry - 1).coerceIn(0, 9)).coerceAtMost(1800)
        val safeCode = (error as? AbstractException)?.reasonCode?.takeIf { it.matches(Regex("^STATION_[A-Z0-9_]{1,42}$")) }
            ?: "STATION_ONLINE_PROCESSING_FAILED"
        finalization.finalizeFailure(sourceId, workerId, status, retry, delay, businessFailure, safeCode)
    }

    /** 执行可能耗时的 Station 外部操作并定时续租；续租失败时释放结果并禁止最终状态提交。 */
    private fun <T> withLeaseRenewal(
        sourceId: Long,
        releaseOnLeaseFailure: (T) -> Unit = {},
        action: () -> T
    ): T {
        channels.renewLease(sourceId, workerId)
        val heartbeatFailure = AtomicReference<Throwable?>()
        val heartbeat: ScheduledFuture<*> = leaseHeartbeat.scheduleAtFixedRate({
            runCatching { channels.renewLease(sourceId, workerId) }
                .onFailure { heartbeatFailure.compareAndSet(null, it) }
        }, 10, 10, TimeUnit.SECONDS)
        return try {
            val result = action()
            try {
                heartbeatFailure.get()?.let { failure ->
                    throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_CHANNEL_LEASE_LOST", cause = failure)
                }
                channels.renewLease(sourceId, workerId)
            } catch (leaseFailure: Throwable) {
                try {
                    releaseOnLeaseFailure(result)
                } catch (cleanupFailure: Throwable) {
                    leaseFailure.addSuppressed(cleanupFailure)
                }
                throw leaseFailure
            }
            result
        } finally {
            heartbeat.cancel(false)
        }
    }

    @PreDestroy
    /** 关闭租约续期线程并等待有限时间，避免应用停止后残留后台任务。 */
    fun shutdownLeaseHeartbeat() = leaseHeartbeat.shutdown()
}

/** 判定附件证据失败是否可作为终态缺失处理；系统错误和未知错误必须继续抛出。 */
internal fun isTerminalStationEvidenceUnavailable(error: AbstractException): Boolean {
    val code = error.reasonCode?.takeIf { it.startsWith("STATION_ATTACHMENT_") } ?: return false
    if (error is ClientException) {
        return true
    }
    if (error !is RemoteException) {
        return false
    }
    val http = Regex("^STATION_ATTACHMENT_HTTP_(\\d{3})$").matchEntire(code)?.groupValues?.get(1)?.toInt()
    if (http != null) {
        return http in 400..499 && http != 429
    }
    return code in setOf(
        "STATION_ATTACHMENT_EMPTY",
        "STATION_ATTACHMENT_SIZE_INVALID",
        "STATION_ATTACHMENT_TOO_LARGE",
        "STATION_ATTACHMENT_FILE_TYPE_INVALID",
        "STATION_ATTACHMENT_MIME_MISMATCH"
    )
}

/** 判定联系方式失败是否可安全降级为不可用；授权或系统错误不得被吞掉。 */
internal fun isTerminalStationContactUnavailable(error: ClientException): Boolean =
    error.reasonCode in setOf(
        "STATION_CONTACT_VALUE_INVALID","STATION_PHONE_FORMAT_INVALID","STATION_UC_USER_ID_MISSING",
        "STATION_PHONE_CIPHERTEXT_TOO_LONG","STATION_PHONE_BASE64_INVALID","STATION_PHONE_DECRYPT_FAILED"
    )
