package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertMetricSourceMapper{
 fun countSince(@Param("minutes")minutes:Int):Double
 fun maxStale():Double?
 fun channelStale():List<MetricChannelStalePo>
 fun pageSince(@Param("minutes")minutes:Int,@Param("after")after:Long,@Param("limit")limit:Int):List<MetricSourcePo>
 fun countBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Long
 fun pageBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime,@Param("after")after:Long,@Param("limit")limit:Int):List<MetricSourcePo>
 fun channelStaleAt(@Param("end")end:LocalDateTime):List<MetricChannelStalePo>
 fun byIds(@Param("ids")ids:List<Long>):List<MetricSourcePo>
}

