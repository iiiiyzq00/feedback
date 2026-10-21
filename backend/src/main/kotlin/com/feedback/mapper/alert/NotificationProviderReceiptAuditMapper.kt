package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface NotificationProviderReceiptAuditMapper {
    fun insert(
        @Param("callback") callback: String,
        @Param("provider") provider: String,
        @Param("channel") channel: String,
        @Param("message") message: String,
        @Param("notification") notification: Long?,
        @Param("target") target: String?,
        @Param("event") event: String,
        @Param("digit") digit: String?,
        @Param("time") time: LocalDateTime,
        @Param("sha") sha: String,
    ): Int
    fun isLatest(
        @Param("notification") notification: Long,
        @Param("provider") provider: String,
        @Param("callback") callback: String,
    ): Int
    fun receipts(@Param("provider") provider: String?, @Param("channel") channel: String?, @Param("limit") limit: Int): List<ProviderReceiptAuditPo>
}

