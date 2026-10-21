package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface DistributionEventMapper { fun insertRollback(@Param("row") row:DistributionRollbackEventPo):Int }

