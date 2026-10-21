package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertMetricContentMapper{
 fun bySources(@Param("ids")ids:List<Long>):List<MetricContentPo>
 fun backlog():Double
 fun pendingSourcePage(@Param("after")after:Long,@Param("limit")limit:Int):List<Long>
}

