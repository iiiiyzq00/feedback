package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface AlertIncidentLifecycleAuditMapper {
    fun timeline(@Param("operation") operation: String, @Param("type") type: String, @Param("id") id: Long): List<LifecycleTimelinePo>
    fun insert(
        @Param("actor") actor: Long?,
        @Param("operation") operation: String,
        @Param("type") type: String,
        @Param("target") target: String,
        @Param("id") id: Long,
        @Param("from") from: String,
        @Param("to") to: String,
        @Param("version") version: Long,
        @Param("reason") reason: String,
        @Param("evidenceJson") evidenceJson: String,
    ): Int
}

