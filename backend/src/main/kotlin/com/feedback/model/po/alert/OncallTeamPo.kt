package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class OncallTeamPo(var team:String="",var members:Long=0,var leaderId:Long?=null)

