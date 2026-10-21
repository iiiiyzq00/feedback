package com.feedback.mapper.audit
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
@Mapper interface AttachmentAuditMapper { fun insertAccessAudit(@Param("userId") userId:Long,@Param("operation") operation:String,@Param("targetType") targetType:String,@Param("targetId") targetId:String,@Param("detailJson") detailJson:String):Int }
