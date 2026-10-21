package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface DistributionContentMapper { fun updateLegacy(@Param("sourceId") sourceId:Long,@Param("status") status:String):Int }

