package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import com.feedback.model.masking.MaskingAudit
import org.apache.ibatis.annotations.Mapper

@Mapper
interface MaskingAuditMapper {
    fun insert(audit: MaskingAudit): Int
}

