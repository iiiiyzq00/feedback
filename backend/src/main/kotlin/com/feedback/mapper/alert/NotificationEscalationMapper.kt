package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface NotificationEscalationMapper{
 fun insertChain(@Param("row")row:EscalationChainInsertPo):Int;fun chainByAlert(@Param("alert")alert:Long,@Param("runningOnly")runningOnly:Boolean):EscalationChainPo?;fun chainByDisplay(@Param("id")id:Long):EscalationChainPo?;fun chainByInternal(@Param("id")id:Long):EscalationChainPo?
 fun targetCount(@Param("chain")chain:Long):Int;fun insertTargets(@Param("rows")rows:List<EscalationTargetInsertPo>):Int;fun target(@Param("chain")chain:Long,@Param("order")order:Int):EscalationTargetPo?;fun targetByDisplay(@Param("id")id:Long):EscalationTargetPo?
 fun updateSeverity(@Param("alert")alert:Long,@Param("severity")severity:String):Int;fun escalationExists(@Param("alert")alert:Long,@Param("channel")channel:String):Int;fun insertEscalation(@Param("row")row:EscalationNotificationInsertPo):Int
 fun insertBaseline(@Param("rows")rows:List<EscalationNotificationInsertPo>):Int;fun baselineCount(@Param("alert")alert:Long,@Param("channels")channels:List<String>):Int;fun baselineExists(@Param("alert")alert:Long,@Param("channel")channel:String):Int
 fun firstContent(@Param("chain")chain:Long):String?;fun insertStandalone(@Param("row")row:EscalationNotificationInsertPo):Int;fun markTargetProgress(@Param("id")id:Long):Int;fun updateDeadline(@Param("id")id:Long,@Param("target")target:Int,@Param("channel")channel:Int,@Param("seconds")seconds:Int):Int;fun insertEscalationNotification(@Param("row")row:EscalationNotificationInsertPo):Int
 fun notification(@Param("id")id:Long):EscalationNotificationPo?;fun advanceIfCurrent(@Param("id")id:Long,@Param("target")target:Int,@Param("channel")channel:Int,@Param("reason")reason:String):Int
 fun acknowledgeChain(@Param("id")id:Long,@Param("user")user:Long?):Int;fun acknowledgeTarget(@Param("id")id:Long?):Int;fun acknowledgeNotification(@Param("id")id:Long,@Param("type")type:String):Int
 fun acknowledgeAlert(@Param("alert")alert:Long,@Param("actor")actor:Long):Int;fun cancelAlert(@Param("alert")alert:Long):Int;fun timedOut():List<EscalationTimeoutPo>;fun advanceTimedOut(@Param("row")row:EscalationTimeoutPo,@Param("reason")reason:String):Int
 fun failTarget(@Param("chain")chain:Long,@Param("order")order:Int):Int;fun advanceTarget(@Param("row")row:EscalationChainPo,@Param("reason")reason:String):Int;fun exhaust(@Param("id")id:Long,@Param("reason")reason:String,@Param("code")code:String):Int
}

