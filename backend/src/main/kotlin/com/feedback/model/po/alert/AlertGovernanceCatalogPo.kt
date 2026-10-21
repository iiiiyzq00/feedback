package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertGovernanceCatalogPo(var ruleCode:String="",var ruleName:String="",var category:String="",var defaultSeverity:String="",var ownerTeam:String?=null,var evaluationMode:String?=null,var implementationStatus:String?=null,var governanceStatus:String?=null,var parameterTemplate:String?=null,var recoveryPolicy:String?=null,var dataDependencies:String?=null,var p0NonSilenceable:Boolean=false,var sourceReference:String?=null,var preventionClass:String?=null,var preventionMeasures:String?=null,var automatedGates:String?=null)

