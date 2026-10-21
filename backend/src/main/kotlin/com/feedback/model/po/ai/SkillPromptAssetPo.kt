package com.feedback.model.po.ai

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class SkillPromptAssetPo(
    var id: Long = 0,
    var recordType: String = "",
    var skillCode: String = "",
    var skillName: String? = null,
    var skillVersion: String? = null,
    var riskLevel: String? = null,
    var lifecycleStatus: String = "",
    var approvalStatus: String? = null,
    var inputSchema: String? = null,
    var outputSchema: String? = null,
    var createdAt: LocalDateTime? = null,
    var createdBy: Long? = null,
    var isCurrent: Boolean = false,
    var promptVersion: String? = null,
    var promptTemplate: String? = null,
    var contentSha256: String? = null,
    var modelConfig: String? = null,
    var releaseNotes: String? = null,
    var publishedAt: LocalDateTime? = null,
    var previousPromptVersionId: Long? = null,
    var rolloutPercent: Int = 0,
    var shadowPercent: Int = 0,
    var skillVersionSnapshot: String? = null,
    var evaluationSummary: String? = null,
    var emergencyDisabled: Boolean = false,
)

