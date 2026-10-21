package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface MetricSourceMapper {
    fun findIdsAfter(@Param("start") start: LocalDateTime, @Param("channels") channels: Set<String>,
                     @Param("regions") regions: Set<String>?, @Param("afterId") afterId: Long,
                     @Param("limit") limit: Int): List<Long>
}

