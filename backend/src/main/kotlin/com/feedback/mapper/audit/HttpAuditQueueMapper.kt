package com.feedback.mapper.audit
import com.feedback.model.po.audit.HttpAuditQueueInsertPo
import com.feedback.model.po.audit.HttpAuditTaskPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
@Mapper interface HttpAuditQueueMapper { fun enqueue(@Param("row") row:HttpAuditQueueInsertPo):Int; fun claim(@Param("holder") holder:String):Int; fun findClaimed(@Param("holder") holder:String):HttpAuditTaskPo?; fun complete(@Param("id") id:Long):Int; fun fail(@Param("id") id:Long,@Param("status") status:String,@Param("retry") retry:Int,@Param("terminal") terminal:Boolean,@Param("delay") delay:Long,@Param("error") error:String):Int }
