package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper
interface MaskingEvaluationAuditMapper { fun insert(@Param("row") row: MaskingEvaluationAuditInsertPo): Int }

