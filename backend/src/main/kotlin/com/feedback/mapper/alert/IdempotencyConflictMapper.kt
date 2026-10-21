package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface IdempotencyConflictMapper {
    fun insert(@Param("row") row:IdempotencyConflictInsertPo):Int
    fun find(@Param("status") status:String?):List<IdempotencyConflictPo>
    fun resolve(@Param("id") id:Long,@Param("status") status:String,@Param("action") action:String,
                @Param("note") note:String,@Param("actor") actor:Long):Int
}

