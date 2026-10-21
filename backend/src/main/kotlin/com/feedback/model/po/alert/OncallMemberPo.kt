package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class OncallMemberPo(var id:Long=0,var team:String="",var userId:Long=0,var role:String="",var primary:Boolean=false,var active:Boolean=true)

