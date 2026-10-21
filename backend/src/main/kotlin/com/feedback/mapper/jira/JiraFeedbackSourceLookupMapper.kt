package com.feedback.mapper.jira

import org.apache.ibatis.annotations.Mapper

@Mapper
interface JiraFeedbackSourceLookupMapper {
    fun findJiraSourceId(jiraKey: String): Long?
}
