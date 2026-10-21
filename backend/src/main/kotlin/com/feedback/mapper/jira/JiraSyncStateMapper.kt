package com.feedback.mapper.jira

import com.feedback.model.po.jira.JiraSyncStatePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface JiraSyncStateMapper {
    fun find(connectionKey: String): JiraSyncStatePo?
    fun insertIgnore(@Param("connectionKey") connectionKey: String, @Param("instanceHash") instanceHash: ByteArray, @Param("status") status: String): Int
    fun refresh(@Param("connectionKey") connectionKey: String, @Param("instanceHash") instanceHash: ByteArray, @Param("status") status: String): Int
    fun enqueue(
        @Param("connectionKey") connectionKey: String,
        @Param("trigger") trigger: String,
        @Param("from") from: LocalDateTime?,
        @Param("to") to: LocalDateTime?,
        @Param("actor") actor: Long?
    ): Int
    fun enqueueScheduledIfDue(connectionKey: String): Int
    fun claim(@Param("connectionKey") connectionKey: String, @Param("workerId") workerId: String): Int
    fun heartbeat(@Param("connectionKey") connectionKey: String, @Param("workerId") workerId: String): Int
    fun complete(
        @Param("connectionKey") connectionKey: String,
        @Param("workerId") workerId: String,
        @Param("watermark") watermark: LocalDateTime,
        @Param("batchDatabaseId") batchDatabaseId: Long?,
        @Param("intervalMinutes") intervalMinutes: Int
    ): Int
    fun fail(
        @Param("connectionKey") connectionKey: String,
        @Param("workerId") workerId: String,
        @Param("delaySeconds") delaySeconds: Long?,
        @Param("code") code: String,
        @Param("detail") detail: String
    ): Int
}
