package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class MaskingEvidenceInsertPo(
    var id: Long = 0, val configType: String, val configVersion: String, val configValueJson: String,
    val scopeKey: String, val configSubtype: String, val subjectUserId: Long, val recordStatus: String,
    val recordVersion: Long, val sourceConfigId: Long? = null, val effectiveTo: LocalDateTime? = null,
    val createdBy: Long
)

