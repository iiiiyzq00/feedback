package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReportIdentityPo(
    var sourceId: Long = 0,
    var scope: String = "",
    var type: String = "",
    var keyId: String = "",
    var fingerprint: ByteArray = byteArrayOf(),
)

