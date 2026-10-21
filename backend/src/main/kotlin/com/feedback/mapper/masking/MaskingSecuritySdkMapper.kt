package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface MaskingSecuritySdkMapper {
    fun count(@Param("view") view: String): Long
    fun list(@Param("view") view: String, @Param("limit") limit: Int, @Param("offset") offset: Long): List<MaskingQuarantinePo>
    fun retry(@Param("receipt") receipt: ByteArray): Int
    fun close(@Param("receipt") receipt: ByteArray, @Param("reason") reason: String,
              @Param("note") note: String, @Param("actor") actor: Long): Int
    fun findRaw(@Param("receipt") receipt: ByteArray): MaskingSdkRawPo?
}

