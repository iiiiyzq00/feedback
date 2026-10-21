package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface DistributionAssignmentMapper {
    fun findOwners(@Param("sourceIds") sourceIds:List<Long>):List<DistributionOwnerPo>
    fun deleteOwner(@Param("sourceId") sourceId:Long,@Param("owner") owner:Long):Int
    fun restoreOwner(@Param("sourceId") sourceId:Long,@Param("from") from:Long,@Param("to") to:Long,
                     @Param("actor") actor:Long):Int
}

