package com.feedback.mapper.jira

import com.feedback.model.po.jira.JiraAttachmentTaskPo
import com.feedback.model.po.jira.JiraAttachmentTaskSummaryPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface JiraAttachmentTaskMapper {
    fun summary(): JiraAttachmentTaskSummaryPo?
    fun upsert(@Param("sourceId") sourceId: Long, @Param("jiraKey") jiraKey: String, @Param("jiraUpdatedAt") jiraUpdatedAt: LocalDateTime): Int
    fun resetBackfill(@Param("sourceId") sourceId: Long, @Param("jiraKey") jiraKey: String, @Param("jiraUpdatedAt") jiraUpdatedAt: LocalDateTime): Int
    fun findClaimableIds(@Param("limit") limit: Int): List<Long>
    fun claimByIds(@Param("ids") ids: List<Long>, @Param("workerId") workerId: String, @Param("leaseMinutes") leaseMinutes: Int): Int
    fun findClaimedByWorker(workerId: String): List<JiraAttachmentTaskPo>
    fun complete(@Param("id") id: Long, @Param("workerId") workerId: String): Int
    fun fail(
        @Param("id") id: Long,
        @Param("workerId") workerId: String,
        @Param("status") status: String,
        @Param("retry") retry: Int,
        @Param("terminal") terminal: Boolean,
        @Param("delay") delay: Int,
        @Param("code") code: String,
        @Param("detail") detail: String
    ): Int
    fun completeWorker(workerId: String): Int
    fun retryFailed(): Int
}
