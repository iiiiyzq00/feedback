package com.feedback.model.imports

import java.time.LocalDateTime

/** 导入模板领域配置；版本化表头与字段映射用于确定性解析。 */
data class ImportTemplate(
    var id: Long = 0,
    val templateName: String,
    val sourceChannel: String,
    val version: String,
    val schemaVersion: String,
    val isActive: Boolean = true,
    val headerColumns: String,
    val fieldMappings: String,
    val createdBy: Long? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
