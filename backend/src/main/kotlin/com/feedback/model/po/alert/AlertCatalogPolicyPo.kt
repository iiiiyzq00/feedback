package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertCatalogPolicyPo(var ruleCode:String="",var defaultSeverity:String?=null,var p0NonSilenceable:Boolean=false)

