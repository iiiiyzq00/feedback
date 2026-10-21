package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface DeadLetterAuditMapper {
 fun evidence(@Param("id")id:Long):List<DeliveryEvidencePo>
 fun insertEvidence(@Param("row")row:DeliveryEvidenceInsertPo):Int
 fun insertAction(@Param("actor")actor:Long,@Param("operation")operation:String,@Param("id")id:String,@Param("detailJson")detailJson:String):Int
}

