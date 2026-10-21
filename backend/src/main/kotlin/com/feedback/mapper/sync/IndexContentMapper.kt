package com.feedback.mapper.sync

import com.feedback.model.po.sync.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface IndexContentMapper {
    fun findByIds(@Param("ids") ids: List<Long>): List<IndexContentPo>
    fun findById(@Param("id") id: Long): IndexContentPo?
}

