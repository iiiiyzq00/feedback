package com.feedback.model.po.config

import java.time.LocalDateTime

data class SystemConfigPo(
    var id: Long = 0,
    var configKey: String = "",
    var configVersion: String = "",
    var configValue: String = "",
    var configDesc: String? = null,
    var isActive: Boolean = false,
    var changeSetId: String? = null,
    var changeReason: String? = null,
    var sourceConfigId: Long? = null,
    var createdBy: Long? = null,
    var createdAt: LocalDateTime = LocalDateTime.MIN,
    var updatedAt: LocalDateTime = LocalDateTime.MIN
)
