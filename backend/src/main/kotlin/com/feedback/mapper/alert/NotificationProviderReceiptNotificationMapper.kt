package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface NotificationProviderReceiptNotificationMapper {
    fun findLatestIdForUpdate(@Param("message") message: String, @Param("channel") channel: String): Long?
    fun markDelivered(@Param("id") id: Long): Int
    fun markConnected(@Param("id") id: Long): Int
    fun markFailed(@Param("id") id: Long, @Param("event") event: String, @Param("summary") summary: String): Int
}

