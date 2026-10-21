package com.feedback.model.po.knowledge

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class KnowledgeSearchPo(
    var id: Long = 0, var title: String = "", var content: String = "", var featureModule: String? = null,
    var issueType: String? = null, var effectivenessScore: BigDecimal? = null, var knowledgeSource: String? = null,
    var sourceRcaId: Long? = null, var supersedesId: Long? = null, var lifecycleVersion: Long? = null,
    var publishedAt: LocalDateTime? = null, var expiresAt: LocalDateTime? = null, var traceReference: String = "",
    var matchReason: String = ""
)

