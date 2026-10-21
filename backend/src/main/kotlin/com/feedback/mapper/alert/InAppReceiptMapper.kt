package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface InAppReceiptMapper {
    fun byTargets(
        @Param("user") user: Long,
        @Param("type") type: String,
        @Param("ids") ids: List<String>,
    ): List<InAppReceiptPo>

    fun insert(
        @Param("user") user: Long,
        @Param("type") type: String,
        @Param("target") target: String,
        @Param("key") key: String,
    ): Int
    fun insertBatch(@Param("rows") rows: List<InAppReceiptInsertPo>): Int
}

