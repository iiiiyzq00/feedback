package com.feedback.mapper.jira.owner

import com.feedback.model.po.jira.owner.JiraOwnerAssigneePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface JiraOwnerAssigneeMapper {
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<JiraOwnerAssigneePo>
}
