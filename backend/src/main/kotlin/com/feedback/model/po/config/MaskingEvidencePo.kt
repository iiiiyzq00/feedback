package com.feedback.model.po.config

import java.time.LocalDateTime

data class MaskingEvidencePo(
    var configKey: String = "",
    var configVersion: String = "",
    var configValue: String = "",
    var configSubtype: String? = null,
    var recordStatus: String? = null,
    var recordVersion: Long? = null,
    var effectiveTo: LocalDateTime? = null
)
