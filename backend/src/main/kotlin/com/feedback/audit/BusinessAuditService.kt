package com.feedback.audit

import com.feedback.common.SafeErrorDetail
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.audit.BusinessAuditInsertPo
import com.feedback.model.po.audit.HttpAuditQueueInsertPo
import com.feedback.model.po.audit.HttpAuditTaskPo
import com.feedback.repository.audit.BusinessAuditRepository
import org.springframework.stereotype.Service
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class BusinessAuditEvent(val actor:Long?,val operation:String,val auditClass:String,val outcome:String,val targetType:String,val targetId:String?,val requestId:String,val clientIp:String?,val detail:Map<String,Any?>)

@Service
class BusinessAuditService(private val repository:BusinessAuditRepository,private val mapper:ObjectMapper){
    fun enqueue(event:BusinessAuditEvent){
        validate(event);val detail=mapper.writeValueAsString(sanitize(event.detail))
        repository.enqueue(HttpAuditQueueInsertPo(event.actor,event.operation,event.auditClass,event.outcome,
            event.targetType.take(50),event.targetId?.take(100),event.requestId,event.clientIp?.take(50),detail))
    }

    @Transactional
    fun append(event:BusinessAuditEvent){
        validate(event)
        require(repository.acquire()){"AUDIT_CHAIN_LOCK_TIMEOUT"}
        try{val detail=sanitize(event.detail);val inputJson=mapper.writeValueAsString(detail);val json=repository.canonicalize(inputJson)
            val previous=repository.latestHash();val material=listOf("2",previous.orEmpty(),event.requestId,event.actor?.toString().orEmpty(),event.operation,event.auditClass,event.outcome,event.targetType,event.targetId.orEmpty(),json).joinToString("|");val hash=sha256(material)
            repository.append(BusinessAuditInsertPo(event.actor,event.operation,event.auditClass,event.outcome,
                event.targetType.take(50),event.targetId?.take(100),event.requestId,json,previous,hash,event.clientIp?.take(50)))}
        finally{repository.release()}
    }
    private fun validate(event:BusinessAuditEvent){require(event.operation.matches(Regex("^[A-Z][A-Z0-9_]{1,49}$")));require(event.auditClass in setOf("BUSINESS","SECURITY","QUERY","SYSTEM"));require(event.outcome in setOf("SUCCESS","DENIED","FAILED"))}
    fun verify(limit:Int=1000):Map<String,Any>{
        require(limit in 1..10000)
        val rows=repository.latest(limit).reversed()
        var expectedPrevious=rows.firstOrNull()?.previousEventHash
        val brokenLinks=mutableListOf<Long>();val hashMismatches=mutableListOf<Long>()
        rows.forEach{row->
            val id=row.id;val previous=row.previousEventHash
            if(previous!=expectedPrevious)brokenLinks+=id
            val material=listOf("2",previous.orEmpty(),row.requestId,row.userId?.toString().orEmpty(),row.operation,
                row.auditClass,row.outcome,row.targetType,row.targetId.orEmpty(),row.operationDetail).joinToString("|")
            if(sha256(material)!=row.eventHash)hashMismatches+=id
            expectedPrevious=row.eventHash
        }
        return mapOf("checked" to rows.size,"integrityValid" to (brokenLinks.isEmpty()&&hashMismatches.isEmpty()),"chainLinked" to brokenLinks.isEmpty(),"hashesValid" to hashMismatches.isEmpty(),"brokenLinkIds" to brokenLinks,"hashMismatchIds" to hashMismatches,"chainEpoch" to 2,"scope" to "CURRENT_EPOCH_LATEST_$limit")
    }
    private fun sanitize(value:Any?):Any?=when(value){is Map<*,*>->value.entries.associate{entry->val key=entry.key.toString().take(100);key to if(SENSITIVE.containsMatchIn(key))"[REDACTED]" else sanitize(entry.value)};is Iterable<*>->value.take(100).map(::sanitize);is String->sanitizeText(value);is Number,is Boolean,null->value;else->sanitizeText(value.toString())}
    private fun sanitizeText(value:String):String{var text=value.take(1000);PATTERNS.forEach{text=it.replace(text,"[REDACTED]")};return text}
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString(""){"%02x".format(it)}
    companion object{val SENSITIVE=Regex("(?i)password|token|secret|authorization|cookie|cipher|plaintext|description|phone|email|contact");val PATTERNS=listOf(Regex("(?i)(password|token|secret|api[_-]?key)\\s*[:=]\\s*\\S+"),Regex("(?<![0-9])1[3-9][0-9]{9}(?![0-9])"),Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"))}
}

@Component
class HttpAuditQueueWorker(
    private val repository:BusinessAuditRepository,
    private val audits:BusinessAuditService,
    private val mapper:ObjectMapper
){
    fun drain(){repeat(100){val task=claim()?:return;process(task)}}

    private fun claim():HttpAuditTaskPo?{
        val holder="http-audit-${java.util.UUID.randomUUID()}"
        return repository.claim(holder)
    }

    private fun process(task:HttpAuditTaskPo){
        val id=task.id;val retry=task.retryCount
        runCatching{
            @Suppress("UNCHECKED_CAST") val detail=mapper.readValue(task.eventDetail,Map::class.java) as Map<String,Any?>
            audits.append(BusinessAuditEvent(task.actorId,task.operation,task.auditClass,task.outcome,task.targetType,
                task.targetId,task.requestId,task.clientIp,detail))
        }.onSuccess{repository.complete(id)}
         .onFailure{error->val attempts=retry+1;val terminal=attempts>=5;val delay=(1L shl retry.coerceAtMost(5))
             repository.fail(id,if(terminal)"FAILED" else "RETRY_PENDING",attempts,terminal,delay,
                 SafeErrorDetail.summary(error,"audit-append"))}
    }
}
