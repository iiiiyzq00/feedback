package com.feedback.repository.query

import com.feedback.mapper.query.*
import com.feedback.model.po.query.*
import org.springframework.stereotype.Repository

@Repository
class DistributionCompensationRepository(
    private val tasks:DistributionTaskMapper,private val batches:DistributionBatchMapper,
    private val assignments:DistributionAssignmentMapper,private val workflows:DistributionWorkflowMapper,
    private val contents:DistributionContentMapper,private val events:DistributionEventMapper
) {
    fun taskForUpdate(key:String)=tasks.lockByKey(key)
    fun delivered(id:Long,receipt:String?){check(tasks.markDelivered(id,receipt) in 0..1)}
    fun unknown(id:Long,reason:String){check(tasks.markUnknown(id,reason) in 0..1)}
    fun failed(id:Long,reason:String){check(tasks.markFailed(id,reason) in 0..1)}
    fun startCompensation(batchId:Long,taskId:Long,reason:String){
        check(batches.markCompensating(batchId,taskId,reason) in 0..1);tasks.markBatchPending(batchId)
    }
    fun batchForUpdate(id:Long)=batches.lockById(id)
    fun tasksForUpdate(id:Long)=tasks.lockByBatch(id)
    fun owners(sourceIds:List<Long>):Map<Long,Long> {
        if(sourceIds.isEmpty())return emptyMap()
        val rows=assignments.findOwners(sourceIds)
        check(rows.groupBy{it.feedbackSourceId}.values.all{it.size==1}){"DISTRIBUTION_ASSIGNMENT_NOT_UNIQUE"}
        return rows.associate { it.feedbackSourceId to it.ownerUserId }
    }
    fun rollbackTask(task:DistributionTaskPo,batch:DistributionBatchPo,processing:String,legacy:String){
        if(task.fromOwnerUserId==null) assignments.deleteOwner(task.feedbackSourceId,task.toOwnerUserId)
        else assignments.restoreOwner(task.feedbackSourceId,task.fromOwnerUserId!!,task.toOwnerUserId,batch.initiatedBy)
        workflows.rollback(task.feedbackSourceId,processing,batch.initiatedBy)
        contents.updateLegacy(task.feedbackSourceId,legacy)
        tasks.markRolledBack(task.id)
        events.insertRollback(DistributionRollbackEventPo(task.feedbackSourceId,batch.initiatedBy,task.toOwnerUserId,
            task.fromOwnerUserId,processing,task.id,batch.failureReason))
    }
    fun compensated(id:Long){check(batches.markCompensated(id) in 0..1)}
    fun compensationFailed(id:Long,attempts:Int,reason:String,delay:Long){
        check(batches.markFailed(id,attempts,reason,delay) in 0..1);tasks.markBatchCompensationFailed(id)
    }
    fun unknownKeys(limit:Int)=tasks.findUnknownKeys(limit)
    fun dueBatchIds(limit:Int)=batches.findDueIds(limit)
}
