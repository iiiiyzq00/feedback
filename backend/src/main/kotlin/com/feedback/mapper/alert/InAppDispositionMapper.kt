package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface InAppDispositionMapper {
    fun page(@Param("user") user: Long, @Param("before") before: Long?, @Param("limit") limit: Int): List<InAppDispositionPo>
    fun visible(@Param("id") id: Long): InAppDispositionPo?
}

