package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class GovernanceContractPo(var contractCode:String="",var schemaVersion:String="",var ownerTeam:String="",var sourceSystem:String="",var freshnessSeconds:Int=0,var completenessThreshold:Double=0.0,var status:String="",var evidenceSha256:String?=null,var validationSummary:String?=null,var validatedBy:Long?=null,var validatedAt:String?=null,var approvedBy:Long?=null,var approvedAt:String?=null,var expiresAt:LocalDateTime?=null)

