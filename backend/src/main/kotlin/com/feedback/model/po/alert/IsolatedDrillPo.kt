package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IsolatedDrillPo(var rawId:Long=0,var id:Long=0,var parentId:Long=0,var ruleId:Long=0,var ruleCode:String?=null,var title:String?=null,var severity:String?=null,var status:String="",var notificationChannel:String?=null,var productionImpact:Boolean=false,var createdAt:LocalDateTime?=null,var userId:Long=0)

