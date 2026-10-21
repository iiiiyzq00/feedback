package com.feedback.model.po.knowledge

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class JiraKnowledgeJiraPo(
    var sourceId: Long = 0, var jiraKey: String = "", var jiraStatusRaw: String? = null,
    var resolution: String? = null, var fixVersion: String? = null, var commentSummary: String? = null,
    var resolvedAt: LocalDateTime? = null
)

