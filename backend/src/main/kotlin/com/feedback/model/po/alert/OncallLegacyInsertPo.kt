package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class OncallLegacyInsertPo(var id:Long=0,val team:String,val name:String?,val type:String,val userId:Long,
 val start:LocalDateTime,val end:LocalDateTime,val escalationUserId:Long?,val actor:Long)

