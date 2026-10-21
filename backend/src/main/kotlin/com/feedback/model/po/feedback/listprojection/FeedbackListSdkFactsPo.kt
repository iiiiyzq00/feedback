package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListSdkFactsPo(
    override var sourceId: Long = 0, var clientFeedbackId: String? = null, var appId: String? = null,
    var packageName: String? = null, var osVersion: String? = null, var networkType: String? = null,
    var deviceModel: String? = null, var screenOrientation: String? = null, var inputMethods: String? = null,
    var triggerType: String? = null, var batteryLevel: BigDecimal? = null, var storageAvailableMb: Long? = null,
    var integrationMode: String? = null, var contextSource: String? = null, var pageRoute: String? = null,
    var businessId: String? = null, var crashStack: String? = null, var courseId: String? = null,
    var questionId: String? = null, var orderId: String? = null, var apiRequestSummary: String? = null,
    var stateMachine: String? = null, var customEvents: String? = null,
    @get:JsonIgnore var diagnosticTypes: String? = null
) : FeedbackListChannelFactsPo

