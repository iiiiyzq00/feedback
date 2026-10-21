package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryIncidentFullPo(var id:Long=0,var publicId:ByteArray=byteArrayOf(),var incidentTitle:String="",var incidentStatus:String="",var lifecycleVersion:Long=0,var incidentCommander:Long?=null,var investigationStartedAt:LocalDateTime?=null,var incidentSeverity:String="",var openedAt:LocalDateTime=LocalDateTime.MIN,var resolvedAt:LocalDateTime?=null,var resolutionSummary:String?=null,var closedAt:LocalDateTime?=null,var closureSummary:String?=null,var statusChangedBy:Long?=null,var statusReason:String?=null,var archivedAt:LocalDateTime?=null,var createdAt:LocalDateTime=LocalDateTime.MIN,var updatedAt:LocalDateTime=LocalDateTime.MIN)

