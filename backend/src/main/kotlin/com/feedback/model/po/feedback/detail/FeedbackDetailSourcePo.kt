package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackDetailSourcePo(
    var id: Long = 0, var sourceChannel: String = "", var sourceRecordId: String = "",
    var appVersion: String? = null, var provinceCode: String? = null, var cityCode: String? = null,
    var districtCode: String? = null, var faultOccurredAt: LocalDateTime? = null,
    var faultTimePrecision: String? = null, var faultTimeSource: String? = null,
    var faultTimeResolution: String? = null, var ingestTime: LocalDateTime? = null,
    var sourceUpdateTime: LocalDateTime? = null, var importBatchId: Long? = null,
    var dataEnvironment: String = ""
)

/** 已脱敏正文及标准化事实；应用层仍必须验证 deleted=false、maskingStatus=PASS。 */

