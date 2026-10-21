package com.feedback.mapper.sync

import com.feedback.model.po.sync.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface IndexAiTaskMapper {
    fun statusCounts(): List<IndexTaskStatusCountPo>
    fun replay(@Param("id") id: Long, @Param("reason") reason: String): Int
    fun findTaskKeys(@Param("keys") keys: List<String>): List<String>
    fun insertOutbox(@Param("rows") rows: List<IndexOutboxInsertPo>): Int
    fun lockDue(): IndexAiTaskPo?
    fun claim(@Param("id") id: Long, @Param("holder") holder: String): Int
    fun findClaimed(@Param("id") id: Long, @Param("holder") holder: String): IndexAiTaskPo?
    fun complete(@Param("id") id: Long, @Param("holder") holder: String, @Param("generation") generation: Long): Int
    fun fail(@Param("id") id: Long, @Param("holder") holder: String, @Param("generation") generation: Long,
             @Param("status") status: String, @Param("retry") retry: Int,
             @Param("terminal") terminal: Boolean, @Param("delay") delay: Long, @Param("error") error: String): Int
}

