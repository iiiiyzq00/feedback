package com.feedback.service.alert

import com.feedback.repository.alert.NotificationOperationsRepository
import org.springframework.stereotype.Service

@Service
class NotificationOperationsService(private val repository:NotificationOperationsRepository,private val policy:NotificationPolicy){
    fun matrix()=mapOf("policies" to policy.view(),"channels" to NotificationPolicy.CHANNELS.sorted(),"source" to "方案3.8.7")
    fun list(page:Int,size:Int,channel:String?,status:String?,alertInstanceId:Long?):Map<String,Any>{
        ensureAlert(page>=1&&size in 1..100){"DELIVERY_PAGE_INVALID"}
        val normalizedChannel=channel?.takeIf(String::isNotBlank)?.let{policy.normalize(it).also{c->ensureAlert(c in NotificationPolicy.CHANNELS){"DELIVERY_CHANNEL_INVALID"}}}
        val normalizedStatus=status?.takeIf(String::isNotBlank)?.uppercase()?.also{ensureAlert(it in setOf("PENDING","SENT","DELIVERED","FAILED","DEAD_LETTER")){"DELIVERY_STATUS_INVALID"}}
        val total=repository.count(normalizedChannel,normalizedStatus,alertInstanceId)
        val rows=repository.page(normalizedChannel,normalizedStatus,alertInstanceId,size,(page-1)*size).map{r->linkedMapOf<String,Any?>("id" to r.id,"ruleCode" to r.ruleCode,"alertInstanceId" to r.alertInstanceId,"channel" to r.channel,"status" to r.status,"attempts" to r.attempts,"maxRetries" to r.maxRetries,"nextAttemptAt" to r.nextAttemptAt,"lastAttemptAt" to r.lastAttemptAt,"deliveredAt" to r.deliveredAt,"errorType" to r.errorType,"errorSummary" to r.errorSummary,"providerMessageId" to r.providerMessageId,"createdAt" to r.createdAt)}
        return mapOf("records" to rows,"total" to total,"page" to page,"size" to size)
    }
    fun overview(hours:Int):Map<String,Any>{ensureAlert(hours in 1..720){"DELIVERY_WINDOW_INVALID"}
        val byChannel=repository.overview(hours).map{r->mapOf("channel" to r.channel,"total" to r.total,"delivered" to r.delivered,"failed" to r.failed,"openDeadLetters" to r.openDeadLetters,"deliveryRate" to r.deliveryRate)}
        val q=repository.queue();val queue=mapOf("pending" to q.pending,"oldestPendingSeconds" to q.oldestPendingSeconds,"openDeadLetters" to q.openDeadLetters)
        return mapOf("windowHours" to hours,"channels" to byChannel,"queue" to queue,"semantics" to mapOf("delivered" to "供应商或本地适配器已接受，不等于人工ACK","rate" to "DELIVERED/窗口内创建通知"))
    }
}
