package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface EntityClosureContentMapper {
 fun findActive(@Param("ids")ids:List<Long>):List<ClosureContentPo>
 fun close(@Param("ids")ids:List<Long>,@Param("actor")actor:Long):Int
}

