package com.feedback.repository.alert

import com.feedback.mapper.alert.SecuritySignalMapper
import com.feedback.model.po.alert.SecuritySignalPo
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class SecuritySignalRepository(private val mapper:SecuritySignalMapper) {
    fun insertIgnore(row:SecuritySignalPo):Boolean=mapper.insertIgnore(row)==1
    fun countOpen(type:String,level:String?,at:LocalDateTime):Long=mapper.countOpen(type,level,at)
    fun latest(limit:Int)=mapper.findLatest(limit.coerceIn(1,200))
    fun conclude(id:Long,status:String,conclusion:String,actor:Long)=mapper.conclude(id,status,conclusion,actor)==1
}
