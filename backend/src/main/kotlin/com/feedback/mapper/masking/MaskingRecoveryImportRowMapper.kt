package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface MaskingRecoveryImportRowMapper {
    fun requeueLegacyReviews(): Int
    fun findRetryBatchIdsAfter(@Param("afterId") afterId: Long, @Param("limit") limit: Int): List<Long>
}

