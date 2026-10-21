package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface ClusterContentTableMapper {
    fun findByIds(@Param("ids") ids: List<Long>): List<ClusterContentPo>
    fun findShadowSafeByIds(@Param("ids") ids: List<Long>): List<ClusterContentPo>
    fun findEligibleAfter(@Param("afterSourceId") afterSourceId: Long, @Param("limit") limit: Int): List<ClusterContentPo>
}
