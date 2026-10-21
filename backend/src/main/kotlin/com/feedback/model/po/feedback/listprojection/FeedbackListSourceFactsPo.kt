package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListSourceFactsPo(
    var id: Long = 0,
    var sourceChannel: String = "",
    var sourceRecordId: String = "",
    var appVersion: String? = null,
    var packageName: String? = null,
    var provinceCode: String? = null,
    var cityCode: String? = null,
    var importBatchId: Long? = null,
    var faultOccurredAt: LocalDateTime? = null,
    var sourceEventTime: LocalDateTime? = null,
    var faultTimePrecision: String? = null,
    var faultTimeSource: String? = null,
    var faultTimeResolution: String? = null,
    var sourceUpdateTime: LocalDateTime? = null,
    var ingestTime: LocalDateTime? = null,
    var dataEnvironment: String = "",
    var updatedAt: LocalDateTime? = null
)

