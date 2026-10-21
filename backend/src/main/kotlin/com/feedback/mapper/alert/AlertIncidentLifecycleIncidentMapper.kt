package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface AlertIncidentLifecycleIncidentMapper {
    fun lock(@Param("id") id: Long): IncidentLifecycleStatePo?
    fun transition(
        @Param("id") id: Long,
        @Param("target") target: String,
        @Param("version") version: Long,
        @Param("reason") reason: String,
        @Param("actor") actor: Long,
        @Param("summary") summary: String?,
        @Param("commander") commander: Long?,
    ): Int
}

