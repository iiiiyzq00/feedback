package com.feedback.service.query

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import org.slf4j.LoggerFactory
import com.feedback.model.po.query.DistributionBatchPo
import com.feedback.repository.query.DistributionCompensationRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class DistributionReceiptState { DELIVERED, FAILED, UNKNOWN }
data class DistributionReceipt(val state:DistributionReceiptState,val receiptId:String?=null,val reason:String?=null)

/** 由提供方适配器实现回执查询。UNKNOWN 永远不会触发回滚。 */
fun interface DistributionReceiptGateway {
    fun lookup(idempotencyKey:String):DistributionReceipt
}

@Component
class NoopDistributionReceiptGateway:DistributionReceiptGateway {
    override fun lookup(idempotencyKey:String)=DistributionReceipt(DistributionReceiptState.UNKNOWN,reason="PROVIDER_RECEIPT_UNAVAILABLE")
}

/**
 * 最终投递结果刻意与通知任务创建分开。
 * 某一项失败时，会在一个事务里补偿它所属的整个投递批次。
 */
@Service
class FeedbackDistributionCompensationService(
    private val repository:DistributionCompensationRepository,
    private val receiptGateway:DistributionReceiptGateway
) {
    private val log=LoggerFactory.getLogger(javaClass)

    @Transactional
    fun confirmDelivered(idempotencyKey:String,receiptId:String?):Boolean {
        val task=taskForUpdate(idempotencyKey)?:return false
        if(task.deliveryStatus=="DELIVERED")return true
        if(task.compensationStatus!="NOT_REQUIRED")return false
        repository.delivered(task.id,receiptId?.take(255))
        return true
    }

    @Transactional
    fun recordUnknown(idempotencyKey:String,reason:String):Boolean {
        val task=taskForUpdate(idempotencyKey)?:return false
        if(task.deliveryStatus in setOf("DELIVERED","FAILED"))return true
        repository.unknown(task.id,safeReason(reason))
        return true
    }

    @Transactional
    fun recordFailed(idempotencyKey:String,reason:String):Long? {
        val task=taskForUpdate(idempotencyKey)?:return null
        val batchId=task.distributionBatchId?:return null
        if(task.deliveryStatus=="DELIVERED"&&task.taskStatus=="ACCEPTED")return batchId
        val safe=safeReason(reason);repository.failed(task.id,safe);repository.startCompensation(batchId,task.id,safe)
        return batchId
    }

    /** 当并发的业务变更使安全回滚无法进行时返回 false。 */
    @Transactional
    fun compensateBatch(batchId:Long):Boolean {
        val batch=repository.batchForUpdate(batchId)?:return false
        if(batch.batchStatus=="COMPENSATED")return true
        val tasks=repository.tasksForUpdate(batchId)
        if(tasks.isEmpty())return markCompensationFailed(batchId,"DISTRIBUTION_BATCH_EMPTY",batch)
        val owners=repository.owners(tasks.map{it.feedbackSourceId})
        val unsafe=tasks.firstOrNull { task ->
            task.taskStatus!="DELIVERED" || owners[task.feedbackSourceId]!=task.toOwnerUserId
        }
        if(unsafe!=null)return markCompensationFailed(batchId,"DISTRIBUTION_STATE_CHANGED:${unsafe.id}",batch)

        tasks.forEach { task ->
            val processing=task.previousProcessingStatus?:"NEW"
            repository.rollbackTask(task,batch,processing,legacyStatus(processing))
        }
        repository.compensated(batchId)
        return true
    }

    fun reconcileUnknown(limit:Int=20):Int {
        requireScanLimit(limit)
        val keys=repository.unknownKeys(limit)
        keys.forEach { key ->
            when(val receipt=receiptGateway.lookup(key)){
                is DistributionReceipt -> when(receipt.state){
                    DistributionReceiptState.DELIVERED->confirmDelivered(key,receipt.receiptId)
                    DistributionReceiptState.FAILED->recordFailed(key,receipt.reason?:"PROVIDER_FINAL_FAILURE")
                    DistributionReceiptState.UNKNOWN->Unit
                }
            }
        }
        return keys.size
    }

    fun dueCompensationBatchIds(limit:Int=20):List<Long> {
        requireScanLimit(limit)
        return repository.dueBatchIds(limit)
    }

    private fun markCompensationFailed(batchId:Long,reason:String,batch:DistributionBatchPo):Boolean {
        val attempts=batch.compensationAttempts+1
        repository.compensationFailed(batchId,attempts,safeReason(reason),1L shl attempts.coerceAtMost(10))
        log.error("DISTRIBUTION_COMPENSATION_FAILED batchId={} attempts={} reason={}",batchId,attempts,reason)
        return false
    }
    private fun taskForUpdate(key:String)=repository.taskForUpdate(key)
    private fun safeReason(reason:String)=reason.trim().take(1000).ifBlank{"PROVIDER_FINAL_FAILURE"}
    private fun requireScanLimit(limit:Int) {
        if (limit !in 1..MAX_SCAN_SIZE) {
            throw ClientException(ErrorCode.BAD_REQUEST, "DISTRIBUTION_COMPENSATION_LIMIT_INVALID")
        }
    }
    private fun legacyStatus(status:String)=when(status){"NEW","DISTRIBUTED"->"OPEN";"PROCESSING","SUSPENDED"->"IN_PROGRESS";"RESOLVED"->"RESOLVED";"CLOSED"->"CLOSED";else->"OPEN"}

    private companion object { const val MAX_SCAN_SIZE = 20 }
}

@Component
class FeedbackDistributionCompensationScheduler(private val service:FeedbackDistributionCompensationService){
    fun reconcile(){
        service.reconcileUnknown()
        service.dueCompensationBatchIds().forEach(service::compensateBatch)
    }
}
