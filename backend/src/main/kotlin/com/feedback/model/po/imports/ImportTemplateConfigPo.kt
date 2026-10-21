package com.feedback.model.po.imports

import java.time.LocalDateTime

data class ImportTemplateConfigPo(
    val legacyId: Long, val templateName: String, val sourceChannel: String,
    val version: String, val schemaVersion: String, val isActive: Boolean,
    val headerColumns: String, val fieldMappings: String, val createdBy: Long?,
    val createdAt: LocalDateTime, val updatedAt: LocalDateTime
)
