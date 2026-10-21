package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface NerAllowlistMapper {
    fun findActiveTerms(): List<String>
    fun findAll(): List<NerAllowlistPo>
    fun countVersions(): Int
    fun insert(@Param("row") row: NerAllowlistInsertPo): Int
    fun retire(@Param("id") id: Long, @Param("expectedVersion") expectedVersion: Long,
               @Param("reason") reason: String): Int
}

