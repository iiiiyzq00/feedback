package com.feedback.mapper.knowledge

import com.feedback.model.po.knowledge.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface JiraKnowledgeSourceMapper {
    fun findJiraIdsAfter(@Param("afterId") afterId: Long, @Param("limit") limit: Int): List<Long>
}

