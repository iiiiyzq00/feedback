package com.feedback.mapper.knowledge

import com.feedback.model.po.knowledge.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface JiraKnowledgeAuditMapper {
    fun insert(@Param("actor") actor: Long, @Param("operation") operation: String,
               @Param("detailJson") detailJson: String): Int
}

