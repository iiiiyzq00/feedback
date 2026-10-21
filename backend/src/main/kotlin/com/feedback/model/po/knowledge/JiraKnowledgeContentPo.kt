package com.feedback.model.po.knowledge

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class JiraKnowledgeContentPo(
    var sourceId: Long = 0, var title: String? = null, var description: String? = null,
    var functionModule: String? = null, var problemType: String? = null
)

