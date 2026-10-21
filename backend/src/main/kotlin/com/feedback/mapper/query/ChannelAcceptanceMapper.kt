package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ChannelAcceptanceMapper {
    fun findLatest(): List<ChannelAcceptancePo>
    fun insert(@Param("channel") channel: String, @Param("status") status: String,
               @Param("comment") comment: String, @Param("actor") actor: Long): Int
}

