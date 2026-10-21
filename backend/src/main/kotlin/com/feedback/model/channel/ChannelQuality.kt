package com.feedback.model.channel

import java.time.LocalDate

/** 品质渠道反馈事实；保留来源业务字段以支持确定性导入、对账与追溯。 */
data class ChannelQuality(
    val sourceId: Long,
    val returnOrderNo: String? = null,
    val productName: String? = null,
    val productModel: String? = null,
    val dispatchDate: LocalDate? = null,
    val activationDate: LocalDate? = null,
    val repairRegion: String? = null,
    val province: String? = null,
    val city: String? = null,
    val county: String? = null,
    val customerAddress: String? = null,
    val district: String? = null,
    val applicationTypeRaw: String? = null,
    val exchangeRequest: Boolean = false,
    val returnReason: String? = null,
    val faultConfirmed: String? = null,
    val faultRemark: String? = null,
    val merchantReturnApplyDate: java.time.LocalDateTime? = null,
    val actualShippingDate: LocalDate? = null
)
