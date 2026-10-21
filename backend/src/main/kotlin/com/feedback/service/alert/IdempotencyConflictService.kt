package com.feedback.service.alert

import com.feedback.security.WebPrincipal
import com.feedback.model.po.alert.IdempotencyConflictInsertPo
import com.feedback.repository.alert.IdempotencyConflictRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

data class ResolveIdempotencyConflictInput(val action:String,val note:String)

@Service
class IdempotencyConflictService(private val repository:IdempotencyConflictRepository){
    fun record(channel:String,recordId:String,batchId:Long,rowId:Long,existingSourceId:Long,existingFingerprint:ByteArray?,incomingFingerprint:ByteArray){
        repository.insert(IdempotencyConflictInsertPo(channel,sha(recordId.toByteArray()),batchId,rowId,existingSourceId,
            existingFingerprint?.let(::sha),sha(incomingFingerprint)))
    }
    fun list(status:String?):List<Map<String,Any?>>{ensureAlert(status==null||status in setOf("OPEN","RESOLVED","IGNORED"));return repository.list(status).map{(row,name)->linkedMapOf("id" to row.id,"sourceChannel" to row.sourceChannel,"importBatchId" to row.importBatchId,"importRowId" to row.importRowId,"existingSourceId" to row.existingSourceId,"reason" to row.reason,"status" to row.status,"resolutionAction" to row.resolutionAction,"resolutionNote" to row.resolutionNote,"occurredAt" to row.occurredAt,"resolvedAt" to row.resolvedAt,"resolvedByName" to name)}}
    @Transactional fun resolve(id:Long,input:ResolveIdempotencyConflictInput):Map<String,Any>{ensureAlert(input.action in setOf("ACCEPT_INCOMING","KEEP_EXISTING","IGNORE"));ensureAlert(input.note.trim().length in 3..500);val actor=(SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId?:alertStateAbort("未认证");val status=if(input.action=="IGNORE")"IGNORED" else "RESOLVED";ensureAlert(repository.resolve(id,status,input.action,input.note.trim(),actor)){"冲突不存在或已处理"};return mapOf("id" to id,"status" to status)}
    private fun sha(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it)}
}
