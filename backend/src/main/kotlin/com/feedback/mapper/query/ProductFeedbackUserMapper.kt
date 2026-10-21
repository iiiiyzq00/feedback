package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ProductFeedbackUserMapper {
    fun countActive(@Param("id") id:Long):Int
    fun findNames(@Param("ids") ids:List<Long>):List<ProductFeedbackUserPo>
}

