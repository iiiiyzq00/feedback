package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface NotificationOperationsMapper{
 fun count(@Param("channel")channel:String?,@Param("status")status:String?,@Param("alertId")alertId:Long?):Long
 fun findPage(@Param("channel")channel:String?,@Param("status")status:String?,@Param("alertId")alertId:Long?,@Param("limit")limit:Int,@Param("offset")offset:Int):List<NotificationOperationPo>
 fun overview(@Param("hours")hours:Int):List<NotificationChannelOverviewPo>
 fun queue():NotificationQueueOverviewPo
}

