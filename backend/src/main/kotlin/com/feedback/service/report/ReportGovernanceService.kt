package com.feedback.service.report

import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.service.auth.DataPermissionService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import com.feedback.repository.report.ReportFreshnessRepository
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@Service
class ReportGovernanceService(
 private val freshness: ReportFreshnessRepository,
 private val permissions:DataPermissionService,
 private val audits:BusinessAuditService,
 @Value("\${app.maintenance.freshness.sdk-seconds:900}") sdkSeconds:Long,
 @Value("\${app.maintenance.freshness.table-seconds:129600}") tableSeconds:Long
) {
 private val thresholds=mapOf("SDK" to sdkSeconds.coerceAtLeast(60),"JIRA" to tableSeconds.coerceAtLeast(3600),"QUALITY" to tableSeconds.coerceAtLeast(3600),"REDBLACK" to tableSeconds.coerceAtLeast(3600))
 fun freshness(now:LocalDateTime=LocalDateTime.now()):Map<String,Any>{
  val channels=permissions.scope().channels.intersect(thresholds.keys)
  val latest=freshness.latest(channels)
  val rows=channels.sorted().map{channel->val time=latest[channel];val lag=time?.let{Duration.between(it,now).seconds.coerceAtLeast(0)};val threshold=thresholds.getValue(channel);mapOf("channel" to channel,"latestIngestTime" to time?.toString(),"lagSeconds" to lag,"lagHours" to lag?.let{java.math.BigDecimal(it).divide(java.math.BigDecimal(3600),1,java.math.RoundingMode.HALF_UP)},"thresholdSeconds" to threshold,"slaStatus" to when{time==null->"NO_DATA";lag!!>threshold->"BREACHED";else->"MET"})}
  val breached=rows.filter{it["slaStatus"]!="MET"}.map{it["channel"]};return mapOf("checkedAt" to now.toString(),"thresholdVersion" to "report-freshness-sla-v1","overallStatus" to if(breached.isEmpty())"MET" else "BREACHED","breachedChannels" to breached,"channels" to rows)
 }
 fun audit(operation:String,outcome:String,actor:Long?,targetId:String?,detail:Map<String,Any?>)=audits.append(BusinessAuditEvent(actor,operation,"BUSINESS",outcome,"AI_REPORT",targetId,UUID.randomUUID().toString(),null,detail))
}
