package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class InAppIncidentPo(var id: Long = 0, var title: String? = null, var status: String? = null)

