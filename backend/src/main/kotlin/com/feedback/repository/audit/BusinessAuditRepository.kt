package com.feedback.repository.audit

import com.feedback.mapper.audit.BusinessAuditChainMapper
import com.feedback.mapper.audit.BusinessAuditLockMapper
import com.feedback.mapper.audit.HttpAuditQueueMapper
import com.feedback.model.po.audit.BusinessAuditInsertPo
import com.feedback.model.po.audit.HttpAuditQueueInsertPo
import com.feedback.model.po.audit.HttpAuditTaskPo
import org.springframework.stereotype.Repository

@Repository
class BusinessAuditRepository(
    private val locks:BusinessAuditLockMapper,
    private val chain:BusinessAuditChainMapper,
    private val queue:HttpAuditQueueMapper
) {
    fun enqueue(row:HttpAuditQueueInsertPo){val changed=queue.enqueue(row);check(changed in 0..1)}
    fun acquire()=locks.acquire()==1
    fun release(){locks.release()}
    fun canonicalize(json:String)=locks.canonicalize(json)
    fun latestHash()=chain.latestHash()
    fun append(row:BusinessAuditInsertPo){check(chain.insert(row)==1)}
    fun latest(limit:Int)=chain.findLatest(limit)
    fun claim(holder:String):HttpAuditTaskPo? = if(queue.claim(holder)==0)null else queue.findClaimed(holder)
    fun complete(id:Long){check(queue.complete(id) in 0..1)}
    fun fail(id:Long,status:String,retry:Int,terminal:Boolean,delay:Long,error:String){
        check(queue.fail(id,status,retry,terminal,delay,error) in 0..1)
    }
}
