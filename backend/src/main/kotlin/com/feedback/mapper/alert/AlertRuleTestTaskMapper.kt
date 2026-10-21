package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertRuleTestTaskMapper{
 fun insert(@Param("row")row:AlertRuleTestInsertPo):Int
 fun page(@Param("before")before:Long?,@Param("limit")limit:Int):List<AlertRuleTestRunPo>
}

