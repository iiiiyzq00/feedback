package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ProductFeedbackRequestMapper {
    fun insert(@Param("row") row:ProductFeedbackCreatePo):Int
    fun count(@Param("status") status:String?,@Param("type") type:String?,@Param("creator") creator:Long?):Long
    fun findPage(@Param("status") status:String?,@Param("type") type:String?,@Param("creator") creator:Long?,
                 @Param("limit") limit:Int,@Param("offset") offset:Int):List<ProductFeedbackRequestPo>
    fun findById(@Param("id") id:Long):ProductFeedbackRequestPo?
    fun lockById(@Param("id") id:Long):ProductFeedbackRequestPo?
    fun update(@Param("row") row:ProductFeedbackUpdatePo):Int
}
