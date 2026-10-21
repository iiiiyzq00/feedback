package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository

@Repository class NotificationDispatchRepository(private val dispatch:NotificationDispatchMapper,private val digestInstances:NotificationDigestInstanceMapper,private val contacts:NotificationContactMapper){
 fun enqueue(row:NotificationEnqueuePo){ensureAlertRepositoryState(dispatch.insert(row)==1)};fun digestExists()=dispatch.digestExists()>0;fun p3Yesterday()=digestInstances.p3Yesterday();fun insertDigest(content:String){ensureAlertRepositoryState(dispatch.insertDigest(content)==1)};fun sourceDelivered(instance:Long,channel:String)=dispatch.sourceDelivered(instance,channel)>0;fun contact(user:Long,kind:String)=contacts.active(user,kind)
}
@Repository class NotificationOutboxRepository(private val outbox:NotificationOutboxMapper,private val attempts:NotificationAttemptMapper,private val audits:NotificationDeadAuditMapper){
 fun claim():NotificationOutboxPo?{val row=outbox.claim()?:return null;ensureAlertRepositoryState(outbox.markSent(row.id)==1);return row}
 fun findClaimed(id:Long):NotificationOutboxPo?=outbox.findClaimed(id)
 fun nextAttempt(id:Long)=attempts.next(id)
 fun record(row:NotificationAttemptPo){ensureAlertRepositoryState(attempts.insert(row)==1)}
 fun delivered(row:NotificationCompletionPo){ensureAlertRepositoryState(outbox.delivered(row)==1){"NOTIFICATION_OUTBOX_STATE_CONFLICT:${row.id}:DELIVERED"}}
 fun deadLetter(row:NotificationCompletionPo,channel:String){
  ensureAlertRepositoryState(outbox.deadLetter(row)==1){"NOTIFICATION_OUTBOX_STATE_CONFLICT:${row.id}:DEAD_LETTER"}
  ensureAlertRepositoryState(audits.insert(row.id,channel,row.errorType,row.attempt)==1){"NOTIFICATION_DEAD_LETTER_AUDIT_WRITE_FAILED:${row.id}"}
 }
 fun failed(row:NotificationCompletionPo){ensureAlertRepositoryState(outbox.failed(row)==1){"NOTIFICATION_OUTBOX_STATE_CONFLICT:${row.id}:FAILED"}}
}
