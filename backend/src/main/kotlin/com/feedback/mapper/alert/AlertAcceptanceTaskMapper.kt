package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface AlertAcceptanceTaskMapper{
    fun findReplay(@Param("id")id:Long):AlertAcceptanceReplayPo?
    fun findDrill(@Param("id")id:Long):AlertAcceptanceDrillPo?
    fun insert(@Param("row")row:AlertAcceptanceInsertPo):Int
    fun approve(@Param("id")id:Long,@Param("actor")actor:Long):Int
    fun findAcceptances(@Param("ruleId")ruleId:Long?):List<AlertAcceptancePo>
}

