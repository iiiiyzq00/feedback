package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListJiraFactsPo(
    override var sourceId: Long = 0, var jiraKey: String? = null, var issueTypeRaw: String? = null,
    var jiraStatusRaw: String? = null, var priorityRaw: String? = null, var projectKey: String? = null,
    var projectName: String? = null, var resolution: String? = null, var assignee: String? = null,
    var jiraCreatedAt: LocalDateTime? = null, var jiraUpdatedAt: LocalDateTime? = null,
    var resolvedAt: LocalDateTime? = null, var affectedVersion: String? = null, var fixVersion: String? = null,
    var moduleRaw: String? = null, var dueDate: LocalDate? = null,
    @get:JsonProperty("label_1") var label1: String? = null,
    @get:JsonProperty("label_2") var label2: String? = null,
    @get:JsonProperty("label_3") var label3: String? = null,
    var commentSummary: String? = null
) : FeedbackListChannelFactsPo

