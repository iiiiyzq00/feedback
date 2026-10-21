package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IdempotencyConflictInsertPo(val channel:String,val recordHash:String,val batchId:Long,val rowId:Long,
    val sourceId:Long,val existingHash:String?,val incomingHash:String)

