package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface MaskingRecoveryClusterLinkMapper {
    fun findActiveMainSourceIds(@Param("sourceIds") sourceIds: List<Long>): List<Long>
}

