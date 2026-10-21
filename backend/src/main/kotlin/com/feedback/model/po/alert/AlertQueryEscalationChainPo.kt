package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryEscalationChainPo(var id:Long=0,var incidentId:Long?=null,var status:String?=null,var currentTarget:Int?=null,var currentChannel:Int?=null,var confirmationDeadline:LocalDateTime?=null,var lastFailureReason:String?=null,var exhaustedReasonCode:String?=null,var notificationRisk:String?=null,var ownerTeam:String?=null,var originalIncidentSeverity:String?=null,var acknowledgedAt:LocalDateTime?=null)

