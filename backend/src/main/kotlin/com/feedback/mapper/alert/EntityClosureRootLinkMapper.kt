package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface EntityClosureRootLinkMapper {
 fun page(@Param("incidentId")incidentId:Long,@Param("afterId")afterId:Long,@Param("limit")limit:Int):List<ClosureRootLinkPo>
 fun count(@Param("incidentId")incidentId:Long):Int
}

