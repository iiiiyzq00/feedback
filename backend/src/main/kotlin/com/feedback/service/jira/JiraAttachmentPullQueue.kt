package com.feedback.service.jira

import com.feedback.common.ErrorCode
import com.feedback.common.SafeErrorDetail
import com.feedback.common.exception.AbstractException
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import jakarta.annotation.PreDestroy
import com.feedback.model.po.jira.JiraAttachmentTaskPo
import com.feedback.repository.jira.JiraAttachmentTaskRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.TransientDataAccessException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

@Service
class JiraAttachmentPullQueue(
    private val persistence:JiraAttachmentTaskRepository,
    private val importer:JiraLogAttachmentImportService
) {
    fun ready()=importer.ready()

    fun summary():Map<String,Long> = persistence.summary()?.let{
        mapOf("pending" to it.pending,"failed" to it.failed)
    }?:mapOf("pending" to 0,"failed" to 0)

    fun enqueue(sourceId:Long,jiraKey:String,jiraUpdatedAt:LocalDateTime) {
        persistence.enqueue(sourceId,jiraKey.uppercase(),jiraUpdatedAt)
    }

    fun enqueueBackfill(jiraKey:String,jiraUpdatedAt:LocalDateTime) {
        persistence.enqueueBackfill(jiraKey.uppercase(),jiraUpdatedAt)
    }

    @Transactional
    fun claim(workerId:String):Map<String,Any?>? {
        return persistence.claim(workerId,1,10).firstOrNull()?.asTaskMap()
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    fun claimBatch(workerId:String,limit:Int=20):List<Map<String,Any?>> {
        if(limit !in 1..20) throw ClientException(ErrorCode.BAD_REQUEST,"JIRA_ATTACHMENT_BATCH_SIZE_INVALID")
        return persistence.claim(workerId,limit,10).map { it.asTaskMap() }
    }

    fun execute(task:Map<String,Any?>,workerId:String) {
        val id=(task["id"] as Number).toLong()
        runCatching {
            val result=importer.pull(listOf(task["jira_key"].toString()))
            if(result.failedFiles!=0) {
                val reason=result.failureCodes.keys.firstOrNull()?.takeIf { it.matches(Regex("^[A-Z][A-Z0-9_]{2,80}$")) }
                    ?:"JIRA_ATTACHMENT_PULL_FAILED"
                throw RemoteException(
                    if(result.hasRetryableFailure)ErrorCode.JIRA_UNAVAILABLE else ErrorCode.JIRA_REQUEST_REJECTED,
                    reason
                )
            }
        }.onSuccess {
            persistence.complete(id,workerId)
        }.onFailure { error ->
            val retry=(task["retry_count"] as Number).toInt()+1
            val governed=error as? AbstractException
            val retryable=governed?.errorCode?.retryable?:true
            val terminal=!retryable||retry>=(task["max_retries"] as Number).toInt()
            persistence.fail(id,workerId,if(terminal)"FAILED" else "RETRY_PENDING",retry,terminal,
                1 shl retry.coerceAtMost(8),governed?.errorCode?.name?:ErrorCode.INTERNAL_ERROR.name,
                SafeErrorDetail.summary(error,"JIRA_ATTACHMENT_PULL"))
        }
    }

    fun executeBatch(tasks:List<Map<String,Any?>>,workerId:String) {
        if(tasks.isEmpty())return
        val result=runCatching{importer.pull(tasks.map{it["jira_key"].toString()})}.getOrNull()
        if(result!=null&&result.failedFiles==0){
            persistence.completeWorker(workerId)
            return
        }
        // 批次级失败无法可靠地归因到某一个 issue。让每个已租约的 issue 走原有的
        // 单任务路径重跑，这样一个坏来源项就不会连累相邻项。
        tasks.forEach{execute(it,workerId)}
    }

    fun retryFailed():Int=persistence.retryFailed()

    private fun JiraAttachmentTaskPo.asTaskMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "jira_key" to jiraKey,
        "retry_count" to retryCount,
        "max_retries" to maxRetries
    )
}

@Component
class JiraAttachmentPullWorker(private val queue:JiraAttachmentPullQueue) {
    private val log=LoggerFactory.getLogger(javaClass)
    private val maxClaimAttempts=3
    fun runOne():Boolean {
        if(!queue.ready())return false
        val workerId="jira-attachment-${UUID.randomUUID()}"
        val task=queue.claim(workerId)?:return false
        runCatching{queue.execute(task,workerId)}
            .onFailure{log.warn("Jira attachment task execution failed code={}",it.javaClass.simpleName)}
        return true
    }
    fun runPending(){repeat(5){if(!runOne())return}}
    fun runBatch():Boolean {
        if(!queue.ready())return false
        val workerId="jira-attachment-batch-${UUID.randomUUID()}"
        val tasks=claimBatchWithRetry(workerId)
        if(tasks.isEmpty())return false
        runCatching{queue.executeBatch(tasks,workerId)}
            .onFailure{log.warn("Jira attachment batch execution failed code={}",it.javaClass.simpleName)}
        return true
    }

    private fun claimBatchWithRetry(workerId:String):List<Map<String,Any?>> {
        repeat(maxClaimAttempts){attempt ->
            try {
                return queue.claimBatch(workerId)
            } catch(error:TransientDataAccessException) {
                if(attempt==maxClaimAttempts-1)throw error
                log.warn("Jira attachment claim contention retry attempt={}",attempt+1)
                Thread.sleep(25L*(attempt+1))
            }
        }
        return emptyList()
    }
}

@Component
class JiraAttachmentPullWorkerScheduler(
    private val worker:JiraAttachmentPullWorker,
    @Value("\${app.jira400.attachment-worker-concurrency:8}") configuredConcurrency:Int
) {
    private val concurrency=configuredConcurrency.coerceIn(1,8)
    private val threadSequence=AtomicInteger()
    private val executor=Executors.newFixedThreadPool(concurrency){task ->
        Thread(task,"jira-evidence-${threadSequence.incrementAndGet()}").apply{isDaemon=true}
    }

    fun runPending() {
        CompletableFuture.allOf(*(0 until concurrency).map {
            CompletableFuture.runAsync({worker.runBatch()},executor)
        }.toTypedArray()).join()
    }

    @PreDestroy
    fun shutdown()=executor.shutdown()
}
