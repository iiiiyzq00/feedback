package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterCommandLedgerPo(
    var id: Long? = null,
    var factKey: String = "",
    var operation: String = "",
    var commandFingerprint: String = "",
    var targetId: Long? = null,
    var requestId: String = "",
    var factStatus: String = "PENDING",
    var evaluationSnapshot: String? = null
)

