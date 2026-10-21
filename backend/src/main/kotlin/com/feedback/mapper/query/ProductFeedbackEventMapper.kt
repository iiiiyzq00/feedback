package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ProductFeedbackEventMapper {
    fun insert(@Param("row") row:ProductFeedbackEventInsertPo):Int
    fun findByRequestId(@Param("requestId") requestId:Long):List<ProductFeedbackEventPo>
}

