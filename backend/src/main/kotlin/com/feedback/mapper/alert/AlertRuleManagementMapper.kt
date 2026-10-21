package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertRuleManagementMapper{
 fun list():List<AlertRuleListPo>
 fun nextPriority():Int
 fun insert(@Param("row")row:AlertRuleInsertPo):Int
 fun lockActive(@Param("id")id:Long):AlertRuleLockedPo?
 fun retire(@Param("id")id:Long):Int
 fun toggle(@Param("id")id:Long,@Param("enabled")enabled:Boolean):Int
 fun lockActiveIds(@Param("ids")ids:List<Long>):List<Long>
 fun reorder(@Param("rows")rows:List<AlertRulePriorityPo>):Int
 fun ruleCode(@Param("id")id:Long):String?
 fun testRules(@Param("ids")ids:List<Long>):List<AlertRuleTestRulePo>
}

