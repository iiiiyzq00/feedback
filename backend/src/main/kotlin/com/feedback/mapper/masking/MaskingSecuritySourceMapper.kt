package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface MaskingSecuritySourceMapper {
    fun findIdByPublicId(@Param("publicId") publicId: String): Long?
    fun findChannel(@Param("id") id: Long): String?
}

