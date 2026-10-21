package com.feedback.manager.station

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.station.online.StationOnlineContentPo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.station.online.StationOnlineChannelRepository
import com.feedback.repository.station.online.StationOnlineContentRepository
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** 每个 source 独立事务，避免一个坏记录回滚同批已完成记录。 */
@Component
class StationOnlineMaskingRecoveryTransaction(
    private val contents: StationOnlineContentRepository,
    private val channels: StationOnlineChannelRepository,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker
) {
    /** 原子提交安全副本和两个投影脏标记，任何一步失败均整体回滚。 */
    @Transactional
    fun markRecovered(content: StationOnlineContentPo) {
        contents.markMaskingPass(content)
        channels.markMaskingRecovered(content.sourceId)
        markOrFail(content.sourceId, FeedbackListProjectionDirtyReason.CONTENT)
        markOrFail(content.sourceId, FeedbackListProjectionDirtyReason.CHANNEL)
    }

    /** 原子记录技术失败和投影脏标记，不写入原始敏感文本。 */
    @Transactional
    fun markTechnicalFailure(sourceId: Long) {
        channels.markMaskingFailure(sourceId, "Station realtime masking recovery failed")
        markOrFail(sourceId, FeedbackListProjectionDirtyReason.CHANNEL)
    }

    /** 将脏标记失败转换为 Station 稳定错误码，避免静默不一致。 */
    private fun markOrFail(sourceId: Long, reason: FeedbackListProjectionDirtyReason) {
        try {
            dirtyMarker.mark(FeedbackListProjectionDirtyCommand(listOf(sourceId), reason))
        } catch (error: Exception) {
            throw ServiceException(ErrorCode.STATION_DIRTY_MARKING_FAILED, "STATION_DIRTY_MARK_FAILED", cause = error)
        }
    }
}
