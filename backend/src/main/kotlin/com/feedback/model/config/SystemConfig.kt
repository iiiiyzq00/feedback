package com.feedback.model.config

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDateTime

/** 版本化系统配置事实；持久化只允许通过 MyBatis Repository 边界。 */
data class SystemConfig(
    val id: Long = 0,
    val configKey: String,
    val configVersion: String,
    val configValue: JsonNode,
    val configSchemaVersion: String = "v1",
    val configDesc: String? = null,
    val isActive: Boolean = true,
    val createdBy: Long? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
