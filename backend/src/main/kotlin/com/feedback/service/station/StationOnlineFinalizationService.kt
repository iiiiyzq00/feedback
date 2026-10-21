package com.feedback.service.station

import com.feedback.model.station.PreparedStationAttachment
import com.feedback.model.station.ResolvedStationPhone
/** Station Worker 收口 Service；外部联系方式和附件 I/O 完成后才进入短写事务。 */
interface StationOnlineFinalizationService {
    /** 批量查询已经持久化的来源附件身份，调用方据此避免重复下载。 */
    fun existingAttachmentIdentities(sourceAttachmentIds: List<String>): Set<String>

    /** 原子提交身份、附件元数据、渠道完成状态与列表投影标脏。 */
    fun finalizeSuccess(
        sourceId: Long,
        leaseOwner: String,
        resolvedPhone: ResolvedStationPhone?,
        contactUnavailable: Boolean,
        preparedAttachments: List<PreparedStationAttachment>,
        unavailableAttachments: Int
    )

    /** 原子提交失败状态与列表投影标脏，不记录异常原文。 */
    fun finalizeFailure(
        sourceId: Long,
        leaseOwner: String,
        status: String,
        retryCount: Int,
        delaySeconds: Long,
        review: Boolean,
        errorCode: String
    )
}
