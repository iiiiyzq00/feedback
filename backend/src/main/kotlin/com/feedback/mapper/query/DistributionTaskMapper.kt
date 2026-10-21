package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface DistributionTaskMapper {
    fun lockByKey(@Param("key") key:String):DistributionTaskPo?
    fun markDelivered(@Param("id") id:Long,@Param("receipt") receipt:String?):Int
    fun markUnknown(@Param("id") id:Long,@Param("reason") reason:String):Int
    fun markFailed(@Param("id") id:Long,@Param("reason") reason:String):Int
    fun markBatchPending(@Param("batchId") batchId:Long):Int
    fun lockByBatch(@Param("batchId") batchId:Long):List<DistributionTaskPo>
    fun markRolledBack(@Param("id") id:Long):Int
    fun markBatchCompensationFailed(@Param("batchId") batchId:Long):Int
    fun findUnknownKeys(@Param("limit") limit:Int):List<String>
}

