package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ProductFeedbackAuditMapper {
    fun insert(@Param("user") user:Long,@Param("operation") operation:String,@Param("targetId") targetId:String,
               @Param("detailJson") detailJson:String):Int
}

