package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository

@Repository class DeadLetterRepository(private val notifications:DeadLetterNotificationMapper,private val occurrences:DeadLetterOccurrenceMapper,private val audits:DeadLetterAuditMapper){
 fun count(channel:String?)=notifications.count(channel)
 fun list(channel:String?,limit:Int,offset:Int)=notifications.list(channel,limit,offset)
 fun notification(id:Long)=notifications.find(id)
 fun attempts(id:Long)=occurrences.attempts(id)
 fun evidence(id:Long)=audits.evidence(id)
 fun replay(id:Long)=notifications.replay(id)==1
 fun ignore(id:Long)=notifications.ignore(id)==1
 fun exists(id:Long)=notifications.exists(id)>0
 fun addEvidence(row:DeliveryEvidenceInsertPo):Long{ensureAlertRepositoryState(audits.insertEvidence(row)==1&&row.id>0);return row.id}
 fun audit(actor:Long,operation:String,id:Long,detailJson:String){ensureAlertRepositoryState(audits.insertAction(actor,operation,id.toString(),detailJson)==1)}
}
