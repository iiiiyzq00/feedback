package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface MaskingSecurityStationMapper {
    fun keep(@Param("sourceId") sourceId: Long, @Param("reason") reason: String): Int
    fun complete(@Param("sourceId") sourceId: Long): Int
}

