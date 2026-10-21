package com.feedback.mapper.knowledge

import com.feedback.model.po.knowledge.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface JiraKnowledgeDraftMapper {
    fun findImportedSourceIds(@Param("sourceIds") sourceIds: List<Long>): List<Long>
    fun insert(@Param("row") row: JiraKnowledgeInsertPo): Int
}

