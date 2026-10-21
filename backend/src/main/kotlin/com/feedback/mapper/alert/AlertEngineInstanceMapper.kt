package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertEngineInstanceMapper{
 fun findOpen(@Param("code")code:String):Long?
 fun insert(@Param("row")row:AlertEngineInstanceInsertPo):Int
 fun state(@Param("id")id:Long):AlertEngineInstanceStatePo?
}

