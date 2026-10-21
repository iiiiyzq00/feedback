package com.feedback.model.po.config

data class SystemConfigInsertPo(
    val configKey: String,
    val configVersion: String,
    val configValue: String,
    val configDesc: String,
    val changeSetId: String,
    val changeReason: String,
    val sourceConfigId: Long?,
    val createdBy: Long
)
