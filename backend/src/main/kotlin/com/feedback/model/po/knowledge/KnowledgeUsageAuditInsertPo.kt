package com.feedback.model.po.knowledge

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class KnowledgeUsageAuditInsertPo(
    val actor: Long,
    val operation: String,
    val targetId: String,
    val correlationId: String,
    val detailJson: String,
    val idempotencyKey: String
)

