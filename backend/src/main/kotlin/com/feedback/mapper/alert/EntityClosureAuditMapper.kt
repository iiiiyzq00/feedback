package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface EntityClosureAuditMapper {
 fun effectiveKnowledge(@Param("ids")ids:List<String>,@Param("correlation")correlation:String):List<ClosureAuditPo>
 fun insertClose(@Param("actor")actor:Long,@Param("id")id:String,@Param("reason")reason:String):Int
}

