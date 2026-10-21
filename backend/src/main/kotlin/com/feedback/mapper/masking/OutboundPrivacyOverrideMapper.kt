package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface OutboundPrivacyOverrideMapper {
    fun countBoundOverride(@Param("sourceId") sourceId: Long, @Param("version") version: String,
                           @Param("hash") hash: ByteArray): Int
}

