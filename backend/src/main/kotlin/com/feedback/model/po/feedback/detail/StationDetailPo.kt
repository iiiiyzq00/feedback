package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class StationDetailPo(
    var sourceApiVersion: String? = null, var productId: String? = null,
    var sourceProductName: String? = null, var sourceAppId: String? = null,
    var sourceAppName: String? = null, var sourcePlatform: String? = null, var sourceFeedbackType: String? = null,
    var sourceSubChannel: String? = null, var sourceDescription: String? = null,
    var attributeSchemaVersion: String? = null, var attributeMappingStatus: String? = null,
    var contactStatus: String? = null, var ingestStatus: String? = null, var processingStage: String? = null,
    var retryCount: Int? = null, var processedAt: LocalDateTime? = null,
    var createdAt: LocalDateTime? = null, var sourceUserId: String? = null, var authenticatedUserId: String? = null
)

/** 详情主表与导航 keyset 的单表 Mapper；不得在这里连接正文表补做权限判断。 */

