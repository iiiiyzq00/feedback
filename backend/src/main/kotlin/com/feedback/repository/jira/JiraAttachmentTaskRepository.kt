package com.feedback.repository.jira

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.jira.JiraAttachmentTaskMapper
import com.feedback.mapper.jira.JiraFeedbackSourceLookupMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class JiraAttachmentTaskRepository(private val tasks: JiraAttachmentTaskMapper, private val sources: JiraFeedbackSourceLookupMapper) {
    fun summary() = tasks.summary()
    fun enqueue(sourceId: Long, key: String, updated: LocalDateTime) { tasks.upsert(sourceId, key, updated) }
    fun enqueueBackfill(key: String, updated: LocalDateTime) {
        val sourceId = sources.findJiraSourceId(key) ?: return
        tasks.resetBackfill(sourceId, key, updated)
    }
    fun claim(worker: String, limit: Int, leaseMinutes: Int) = tasks.findClaimableIds(limit).let { ids ->
        if (ids.isEmpty()) emptyList() else {
            if(tasks.claimByIds(ids, worker, leaseMinutes) != ids.size)
                throw ServiceException(ErrorCode.INTERNAL_ERROR,"JIRA_ATTACHMENT_CLAIM_CONFLICT")
            tasks.findClaimedByWorker(worker)
        }
    }
    fun complete(id: Long, worker: String) = tasks.complete(id, worker)
    fun fail(id: Long, worker: String, status: String, retry: Int, terminal: Boolean, delay: Int, code: String, detail: String) =
        tasks.fail(id, worker, status, retry, terminal, delay, code, detail)
    fun completeWorker(worker: String) = tasks.completeWorker(worker)
    fun retryFailed() = tasks.retryFailed()
}

@Repository
class JiraFeedbackSourceRepository(private val mapper: JiraFeedbackSourceLookupMapper) {
    fun findSourceId(jiraKey: String): Long? = mapper.findJiraSourceId(jiraKey)
}
