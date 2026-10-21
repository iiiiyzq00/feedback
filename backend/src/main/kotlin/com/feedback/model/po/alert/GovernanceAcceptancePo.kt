package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class GovernanceAcceptancePo(var id:Long=0,var status:String?=null,var precisionRate:String?=null,var recallRate:String?=null,var routingOutcome:String?=null,var assessedBy:Long?=null,var approvedBy:Long?=null,var assessedAt:LocalDateTime?=null,var approvedAt:LocalDateTime?=null)

