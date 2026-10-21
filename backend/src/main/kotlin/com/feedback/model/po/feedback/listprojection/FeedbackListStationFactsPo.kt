package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListStationFactsPo(
    override var sourceId: Long = 0, var sourceApiVersion: String? = null, var productId: String? = null,
    var sourceProductName: String? = null, var sourceAppId: String? = null, var sourceAppName: String? = null,
    var sourcePlatform: String? = null, var sourceFeedbackType: String? = null, var sourceSubChannel: String? = null,
    var sourceDescription: String? = null, var attributeSchemaVersion: String? = null,
    var attributeMappingStatus: String? = null, var contactStatus: String? = null, var ingestStatus: String? = null,
    var processingStage: String? = null, var retryCount: Int? = null, var processedAt: LocalDateTime? = null,
    var createdAt: LocalDateTime? = null,
    @get:JsonIgnore var hasSourceUserId: Boolean = false,
    @get:JsonIgnore var hasAuthenticatedUserId: Boolean = false
) : FeedbackListChannelFactsPo

