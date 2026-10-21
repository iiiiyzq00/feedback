package com.feedback.mapper.jira.owner

import com.feedback.model.po.jira.owner.JiraOwnerSourcePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface JiraOwnerSourceMapper {
    fun findPageAfterId(
        @Param("afterSourceId") afterSourceId: Long,
        @Param("limit") limit: Int
    ): List<JiraOwnerSourcePo>
    fun findJiraByIds(@Param("sourceIds") sourceIds: List<Long>): List<JiraOwnerSourcePo>
}
