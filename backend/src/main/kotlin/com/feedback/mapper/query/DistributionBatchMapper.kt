package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface DistributionBatchMapper {
    fun lockById(@Param("id") id:Long):DistributionBatchPo?
    fun markCompensating(@Param("id") id:Long,@Param("taskId") taskId:Long,@Param("reason") reason:String):Int
    fun markCompensated(@Param("id") id:Long):Int
    fun markFailed(@Param("id") id:Long,@Param("attempts") attempts:Int,@Param("reason") reason:String,
                   @Param("delay") delay:Long):Int
    fun findDueIds(@Param("limit") limit:Int):List<Long>
}

