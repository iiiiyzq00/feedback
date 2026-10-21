package com.feedback.model.po.station.online

import java.time.LocalDateTime

/** StationAttachmentIdentityAdoptionPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationAttachmentIdentityAdoptionPo(
    val id: Long,
    val sourceAttachmentId: String
)
