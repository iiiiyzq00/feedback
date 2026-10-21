package com.feedback.mapper.ai

import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface AiReviewSourceMapper {
    fun findScopedAfter(@Param("afterId") afterId: Long, @Param("channels") channels: Set<String>,
                        @Param("regions") regions: Set<String>?, @Param("limit") limit: Int): List<AiReviewSourcePo>
    fun findByIds(@Param("ids") ids: List<Long>): List<AiReviewSourcePo>
}

