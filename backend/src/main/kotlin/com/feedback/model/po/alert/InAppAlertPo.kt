package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class InAppAlertPo(var id: Long = 0, var ruleId: Long = 0, var firedAt: LocalDateTime? = null, var status: String? = null, var severity: String? = null)

