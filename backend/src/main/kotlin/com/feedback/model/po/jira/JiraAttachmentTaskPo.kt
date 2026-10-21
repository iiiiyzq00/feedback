package com.feedback.model.po.jira

data class JiraAttachmentTaskPo(
    val id: Long,
    val jiraKey: String,
    val retryCount: Int,
    val maxRetries: Int
)
