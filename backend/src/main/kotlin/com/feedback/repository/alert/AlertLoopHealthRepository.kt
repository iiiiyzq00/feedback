package com.feedback.repository.alert
import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository
data class AlertLoopRuntime(val lastEvaluationAt:java.time.LocalDateTime?,val openAlerts:Long,val recovered24h:Long,
 val pendingNotifications:Long,val deadLetters:Long,val lastDeliveryAt:java.time.LocalDateTime?)
@Repository class AlertLoopHealthRepository(private val rules:AlertLoopRuleMapper,private val occurrences:AlertLoopOccurrenceMapper,
 private val instances:AlertLoopInstanceMapper,private val notifications:AlertLoopNotificationMapper){
 fun coverage()=rules.coverage()
 fun runtime():AlertLoopRuntime{val i=instances.runtime();val n=notifications.runtime();return AlertLoopRuntime(occurrences.lastEvaluationAt(),i.openAlerts,i.recovered24h,n.pendingNotifications,n.deadLetters,n.lastDeliveryAt)}
}
