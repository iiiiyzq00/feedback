package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface ClusterJiraContextTableMapper { fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ClusterJiraContextPo> }

