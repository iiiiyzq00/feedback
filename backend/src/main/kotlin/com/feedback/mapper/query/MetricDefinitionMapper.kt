package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface MetricDefinitionMapper { fun findActive(@Param("limit") limit: Int): List<MetricDefinitionPo> }
