package com.feedback.mapper.ai

import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface AiReviewAuditMapper {
    fun insertOne(@Param("reviewer") reviewer: Long, @Param("sourceId") sourceId: Long,
                  @Param("action") action: String, @Param("category") category: String?,
                  @Param("severity") severity: String?): Int
    fun insertBatch(@Param("reviewer") reviewer: Long, @Param("target") target: String,
                    @Param("action") action: String, @Param("category") category: String?,
                    @Param("severity") severity: String?, @Param("count") count: Int,
                    @Param("idsJson") idsJson: String): Int
}

