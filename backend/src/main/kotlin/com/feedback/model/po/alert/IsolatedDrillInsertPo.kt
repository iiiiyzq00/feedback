package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IsolatedDrillInsertPo(var id:Long?=null,var ruleId:Long=0,var parentId:Long=0,var ruleCode:String="",var title:String="",var severity:String="",var actor:Long=0)

