package com.feedback.mapper.jira.owner

import com.feedback.model.po.jira.owner.JiraOwnerInitializationWritePo
import com.feedback.model.po.jira.owner.JiraOwnerWorkflowPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface JiraOwnerWorkflowMapper {
    fun findBySourceIdsForUpdate(@Param("sourceIds") sourceIds: List<Long>): List<JiraOwnerWorkflowPo>
    fun acceptBatch(@Param("rows") rows: List<JiraOwnerInitializationWritePo>): Int
}
