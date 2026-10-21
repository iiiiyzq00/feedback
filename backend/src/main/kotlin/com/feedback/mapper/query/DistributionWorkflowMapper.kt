package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface DistributionWorkflowMapper {
    fun rollback(@Param("sourceId") sourceId:Long,@Param("processing") processing:String,@Param("actor") actor:Long):Int
}

