package com.feedback.mapper.ai

import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface AiReviewContentMapper {
    fun findSafe(@Param("ids") ids: List<Long>, @Param("modules") modules: Set<String>?): List<AiReviewContentPo>
    fun findByIds(@Param("ids") ids: List<Long>): List<AiReviewContentPo>
    fun overrule(@Param("sourceId") sourceId: Long, @Param("category") category: String,
                 @Param("severity") severity: String, @Param("reviewer") reviewer: Long): Int
    fun overruleBatch(@Param("ids") ids: List<Long>, @Param("category") category: String,
                      @Param("severity") severity: String, @Param("reviewer") reviewer: Long): Int
    fun adoptBatch(@Param("rows") rows: List<AiReviewAdoptionPo>, @Param("reviewer") reviewer: Long): Int
}

