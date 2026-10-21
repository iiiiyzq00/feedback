package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface MetricContentMapper {
    fun findActive(@Param("sourceIds") sourceIds: List<Long>, @Param("modules") modules: Set<String>?): List<MetricContentFactPo>
}

