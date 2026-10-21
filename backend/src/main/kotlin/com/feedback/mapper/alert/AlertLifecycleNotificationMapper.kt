package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertLifecycleNotificationMapper {
 fun activeSuppressions():List<AlertSuppressionPo>
 fun suppressions():List<AlertSuppressionPo>
 fun overlaps(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime,@Param("ruleId")ruleId:Long?):Int
 fun insertSuppression(@Param("row")row:AlertSuppressionInsertPo):Int
 fun deleteFuture(@Param("id")id:Long):Int
 fun escalationMarkers(@Param("ids")ids:List<Long>):List<AlertEscalationMarkerPo>
 fun insertEscalationNotifications(@Param("rows")rows:List<AlertEscalationNotificationInsertPo>):Int
}

