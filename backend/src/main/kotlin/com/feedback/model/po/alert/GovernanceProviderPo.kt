package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class GovernanceProviderPo(var id:Long?=null,var channel:String="",var providerCode:String="",var environment:String="",var status:String="",var capabilities:String="{}",var dailyQuota:Long=0,var idempotencySupported:Boolean=false,var evidenceSha256:String?=null,var evidenceReference:String?=null,var validatedBy:Long?=null,var approvedBy:Long?=null,var expiresAt:LocalDateTime?=null)

