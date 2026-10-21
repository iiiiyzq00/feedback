package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface SimilarFeedbackSourceMapper {
    fun findVisibleAfter(@Param("afterId") afterId: Long, @Param("channels") channels: Set<String>,
                         @Param("regions") regions: Set<String>?, @Param("limit") limit: Int): List<Long>
}

