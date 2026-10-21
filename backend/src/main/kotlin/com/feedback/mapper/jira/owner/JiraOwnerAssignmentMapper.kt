package com.feedback.mapper.jira.owner

import com.feedback.model.po.jira.owner.JiraOwnerAssignmentPo
import com.feedback.model.po.jira.owner.JiraOwnerInitializationWritePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface JiraOwnerAssignmentMapper {
    fun findBySourceIdsForUpdate(@Param("sourceIds") sourceIds: List<Long>): List<JiraOwnerAssignmentPo>
    fun insertBatch(@Param("rows") rows: List<JiraOwnerInitializationWritePo>): Int
}
