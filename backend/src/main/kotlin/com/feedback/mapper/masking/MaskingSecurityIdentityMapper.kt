package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface MaskingSecurityIdentityMapper {
    fun findAuthorized(@Param("sourceId") sourceId: Long): List<MaskingAuthorizedContactPo>
}

