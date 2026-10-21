package com.feedback.mapper.report

import com.feedback.model.query.report.*
import com.feedback.model.po.report.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface ReportLineageMapper {
    fun setInitial(@Param("id") id: Long, @Param("key") key: String): Int
    fun setRegenerated(
        @Param("id") id: Long,
        @Param("key") key: String,
        @Param("previous") previous: Long?,
        @Param("number") number: Int,
    ): Int
}

