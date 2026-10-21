package com.feedback.service.impl.station

import com.feedback.service.station.*
import com.feedback.model.station.*
import com.feedback.manager.station.StationOnlineMaskingRecoveryTransaction

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.repository.station.online.StationOnlineChannelRepository
import com.feedback.repository.station.online.StationOnlineContentRepository
import com.feedback.repository.station.online.StationOnlineSourceRepository
import com.feedback.service.masking.MaskingPipeline
import com.feedback.service.station.StationOnlineMaskingRecoveryService
import com.feedback.service.station.StationRawPayloadService
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicLong

/** Station Online（仅 REALTIME）自动脱敏恢复编排；每轮最多读取 200 个候选 source。 */
@Service
class StationOnlineMaskingRecoveryServiceImpl(
    private val sources: StationOnlineSourceRepository,
    private val contents: StationOnlineContentRepository,
    private val channels: StationOnlineChannelRepository,
    private val rawPayloads: StationRawPayloadService,
    private val masking: MaskingPipeline,
    private val transactions: StationOnlineMaskingRecoveryTransaction
) : StationOnlineMaskingRecoveryService {
    private val cursor = AtomicLong(0)

    /** 在事务外批量读取、解密和脱敏，逐记录交给短事务提交器更新。 */
    override fun recover(limit: Int): Int {
        if (limit !in 1..200) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_MASKING_BATCH_INVALID")
        }
        val ids = contents.findMaskingReviewSourceIdsAfterId(cursor.get(), limit)
        if (ids.isEmpty()) {
            cursor.set(0)
            return 0
        }
        cursor.set(ids.last())
        val sourceIds = sources.findByIds(ids).map { it.id }.toSet()
        val contentById = contents.findBySourceIds(ids).associateBy { it.sourceId }
        val channelIds = channels.findBySourceIds(ids).map { it.sourceId }.toSet()
        val rawById = rawPayloads.inspectManySafely(ids)

        var recovered = 0
        ids.forEach { sourceId ->
            if (sourceId !in sourceIds || sourceId !in channelIds) {
                return@forEach
            }
            val content = contentById[sourceId] ?: return@forEach
            val raw = rawById[sourceId] ?: return@forEach
            if (raw.path("sourceMode").asText() != "REALTIME") {
                return@forEach
            }
            val recoveredContent = try {
                val title = masking.mask(raw.path("title").asText(""), "station_auto_title_$sourceId")
                val bodySource = raw.path("content").asText("").ifBlank { raw.path("description").asText("") }
                val body = masking.mask(bodySource, "station_auto_content_$sourceId")
                if (title.maskingStatus != "PASS" || body.maskingStatus != "PASS") {
                    throw ServiceException(ErrorCode.MASKING_FAILED, "MASKING_SAFE_COPY_NOT_PRODUCED")
                }
                content.copy(title = title.maskedText, description = body.maskedText)
            } catch (_: Exception) {
                transactions.markTechnicalFailure(sourceId)
                return@forEach
            }
            transactions.markRecovered(recoveredContent)
            recovered++
        }
        return recovered
    }
}
