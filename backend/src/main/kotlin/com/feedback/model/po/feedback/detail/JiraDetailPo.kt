package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class JiraDetailPo(
    var jiraKey: String? = null, var priorityRaw: String? = null, var projectKey: String? = null,
    var jiraStatusRaw: String? = null, var projectName: String? = null, var resolution: String? = null,
    var assignee: String? = null,
    var jiraCreatedAt: LocalDateTime? = null, var jiraUpdatedAt: LocalDateTime? = null,
    var resolvedAt: LocalDateTime? = null, var affectedVersion: String? = null, var fixVersion: String? = null,
    var moduleRaw: String? = null, var dueDate: LocalDate? = null, var label1: String? = null,
    var label2: String? = null, var label3: String? = null, var commentSummary: String? = null
)

