package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface AlertQueryNotificationMapper{
 fun activeSilence(@Param("id")id:Long):AlertQuerySilencePo?;fun activeSilenceCount(@Param("id")id:Long):Int
 fun insertSilence(@Param("id")id:Long,@Param("reason")reason:String,@Param("minutes")minutes:Int,@Param("actor")actor:Long):Int
 fun chain(@Param("id")id:Long):AlertQueryEscalationChainPo?;fun targets(@Param("id")id:Long):List<AlertQueryEscalationTargetPo>
 fun timeline(@Param("id")id:Long):List<AlertQueryNotificationTimelinePo>;fun targetNames(@Param("ids")ids:List<Long>):List<AlertQueryTargetNamePo>
}

