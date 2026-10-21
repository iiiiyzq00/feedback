package com.feedback.model.po.sdk

import java.math.BigDecimal

/** SdkFeedbackContentPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class SdkFeedbackContentPo(
    var sourceId: Long = 0,
    var description: String? = null,
    var functionModule: String? = null,
    var problemType: String? = null,
    var feedbackTags: String? = null,
    var reproducibility: String? = null,
    var mappingRuleVersion: String? = null,
    var normalizationVersion: String? = null,
    var maskingStatus: String = "PENDING",
    var dataQualityScore: BigDecimal? = null,
    var qualityRuleVersion: String? = null
)
