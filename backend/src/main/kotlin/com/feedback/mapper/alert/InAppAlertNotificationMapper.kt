package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface InAppAlertNotificationMapper {
    fun page(@Param("user") user: Long, @Param("before") before: Long?, @Param("limit") limit: Int): List<InAppAlertNotificationPo>
    fun count(@Param("user") user: Long): Long
    fun visible(@Param("id") id: Long, @Param("user") user: Long): Int
    fun visibleAlertIds(@Param("user") user: Long, @Param("ids") ids: List<Long>): List<Long>
}

