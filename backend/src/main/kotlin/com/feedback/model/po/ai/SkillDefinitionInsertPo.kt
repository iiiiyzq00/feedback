package com.feedback.model.po.ai

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class SkillDefinitionInsertPo(
    var skillCode: String = "",
    var skillName: String = "",
    var skillVersion: String = "",
    var inputSchema: String = "",
    var outputSchema: String = "",
    var riskLevel: String = "",
    var createdBy: Long? = null,
    var id: Long? = null,
)

