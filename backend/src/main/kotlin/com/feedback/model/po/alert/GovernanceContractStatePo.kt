package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class GovernanceContractStatePo(var contractCode:String="",var status:String?=null,var expiresAt:LocalDateTime?=null,var ready:Boolean=false)

