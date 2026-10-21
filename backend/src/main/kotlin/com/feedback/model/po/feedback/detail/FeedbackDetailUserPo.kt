package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackDetailUserPo(var id: Long = 0, var displayName: String? = null, var username: String = "")

/** 单条字段转换审计事实；operationDetail 的具名 JSON 解析和字段权限由应用层负责。 */

