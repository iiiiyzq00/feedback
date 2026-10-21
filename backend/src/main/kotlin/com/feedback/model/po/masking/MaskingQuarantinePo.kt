package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MaskingQuarantinePo(
    var receiptId: String = "", var status: String = "", var bodyStatus: String = "",
    var contactStatus: String = "", var urgency: String = "", var retryCount: Int = 0,
    var dispositionStatus: String = "", var dispositionReason: String? = null, var dispositionNote: String? = null,
    var dispositionAt: LocalDateTime? = null, var errorCode: String? = null, var retentionUntil: LocalDateTime? = null,
    var nextRetryAt: LocalDateTime? = null, var receivedAt: LocalDateTime? = null, var retryable: Boolean = false,
    var actionHint: String = ""
)

