package com.feedback.mapper.jira.owner

import com.feedback.model.po.jira.owner.JiraOwnerInitializationWritePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface JiraOwnerDispositionEventMapper {
    fun insertBatch(@Param("rows") rows: List<JiraOwnerInitializationWritePo>): Int
}
