package com.feedback.model.po.ai

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class PromptVersionInsertPo(
    var skillCode: String = "",
    var promptVersion: String = "",
    var promptTemplate: String = "",
    var modelConfig: String = "",
    var isCurrent: Boolean = false,
    var lifecycleStatus: String = "DRAFT",
    var approvalStatus: String = "PENDING",
    var contentSha256: String = "",
    var releaseNotes: String? = null,
    var createdBy: Long? = null,
    var skillVersionSnapshot: String = "",
    var bootstrap: Boolean = false,
    var id: Long? = null,
)

