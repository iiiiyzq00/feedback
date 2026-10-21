package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListQualityFactsPo(
    override var sourceId: Long = 0, var returnOrderNo: String? = null, var productName: String? = null,
    var productModel: String? = null, var dispatchDate: LocalDate? = null, var activationDate: LocalDate? = null,
    var repairRegion: String? = null, var province: String? = null, var city: String? = null,
    var county: String? = null, var district: String? = null, var applicationTypeRaw: String? = null,
    var exchangeRequest: Boolean? = null, var returnReason: String? = null, var faultConfirmed: String? = null,
    var faultRemark: String? = null, var merchantReturnApplyDate: LocalDateTime? = null,
    var actualShippingDate: LocalDate? = null
) : FeedbackListChannelFactsPo

