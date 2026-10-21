package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterSourcePo(
    var id: Long = 0,
    var publicId: ByteArray = ByteArray(0),
    var sourceChannel: String = "",
    var sourceRecordId: String = "",
    var appVersion: String? = null,
    var packageName: String? = null,
    var sourceEventTime: LocalDateTime? = null,
    var ingestTime: LocalDateTime = LocalDateTime.MIN,
    var provinceCode: String? = null,
    var cityCode: String? = null,
    var createdAt: LocalDateTime = LocalDateTime.MIN,
    var updatedAt: LocalDateTime = LocalDateTime.MIN
)

