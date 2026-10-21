package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class GovernanceRulePo(var id:Long=0,var ruleCode:String="",var severity:String="",var ownerTeam:String?=null,var routing:String="{}",var versionStatus:String="")

