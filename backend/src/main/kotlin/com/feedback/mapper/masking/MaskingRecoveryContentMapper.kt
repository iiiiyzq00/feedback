package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface MaskingRecoveryContentMapper {
    fun counts(): MaskingContentCountsPo
    fun findSafeIdsAfter(@Param("afterId") afterId: Long, @Param("limit") limit: Int): List<Long>
}

