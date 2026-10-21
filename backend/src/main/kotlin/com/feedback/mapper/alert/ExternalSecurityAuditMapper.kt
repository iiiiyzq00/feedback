package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ExternalSecurityAuditMapper{
 fun findReceipt(@Param("key")key:String):ExternalSecurityReceiptPo?
 fun insertReceipt(@Param("targetId")targetId:String,@Param("detailJson")detailJson:String,@Param("occurredAt")occurredAt:LocalDateTime,@Param("key")key:String):Int
}

