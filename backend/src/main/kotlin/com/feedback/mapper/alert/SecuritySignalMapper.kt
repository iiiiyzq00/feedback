package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface SecuritySignalMapper {
    fun insertIgnore(row:SecuritySignalPo):Int
    fun countOpen(@Param("type") type:String,@Param("level") level:String?,@Param("at") at:LocalDateTime):Long
    fun findLatest(@Param("limit") limit:Int):List<SecuritySignalPo>
    fun conclude(@Param("id") id:Long,@Param("status") status:String,@Param("conclusion") conclusion:String,@Param("actor") actor:Long):Int
}

