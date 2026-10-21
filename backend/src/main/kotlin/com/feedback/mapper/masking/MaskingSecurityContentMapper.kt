package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface MaskingSecurityContentMapper {
    fun isStationReviewable(@Param("sourceId") sourceId: Long): Int
    fun release(@Param("sourceId") sourceId: Long, @Param("title") title: String?,
                @Param("description") description: String?): Int
}

