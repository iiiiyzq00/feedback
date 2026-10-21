package com.feedback.mapper.sync

import com.feedback.model.po.sync.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface IndexConsistencyAuditMapper {
    fun insert(@Param("operator") operator: Long, @Param("operation") operation: String,
               @Param("targetType") targetType: String, @Param("targetId") targetId: String?,
               @Param("detailJson") detailJson: String): Int
}

